import * as pulumi from '@pulumi/pulumi';
import * as aws from '@pulumi/aws';
import * as awsx from '@pulumi/awsx';
import * as eks from '@pulumi/eks';
import { UserMapping } from '@pulumi/eks';
import * as k8s from '@pulumi/kubernetes';
import * as certmanager from '@pulumi/kubernetes-cert-manager';
import { ingressControllerPolicyConfig } from './policies/ingressControllerPolicy'
import { cloudwatchconfigmap } from './apps/amazon-cloudwatch/cloudwatchConfigMap'
import { cloudwatchClusterRole } from './apps/amazon-cloudwatch/cloudwatchClusterRole' 
import { cloudwatchClusterRoleBinding } from './apps/amazon-cloudwatch/cloudwatchClusterRoleBinding'
import { cloudwatchDaemonSet } from './apps/amazon-cloudwatch/cloudwatchDaemonSet'

// VPC tags need to be configured under global/Networking config files for automatic subnet discovery for LBs and ingress controllers

// --------------------
const cfg = new pulumi.Config();
const users: Array<string> = cfg.requireObject('users') as any;
const region = aws.config.region as string;
const clusterName = <string>cfg.require('clusterName');
const eksVersion = <string>cfg.require('eksVersion');
const nodeInstanceTypes = <string>cfg.require('nodeInstanceTypes');
const nodeDiskSize = <number><unknown>cfg.require('nodeDiskSize');
const nodeDesiredSize = <number><unknown>cfg.require('nodeDesiredSize');
const nodeMinSize = <number><unknown>cfg.require('nodeMinSize');
const nodeMaxSize = <number><unknown>cfg.require('nodeMaxSize');
const helmChartVersion = <string>cfg.require('helmChartVersion');
const env = <string>cfg.require('env');
const networkingRef = new pulumi.StackReference(`center/aws-global-networking/${env}`);
const publicSubnetIds = networkingRef.getOutputDetails('publicSubnetIds')
const privateSubnetIds = networkingRef.getOutputDetails('privateSubnetIds')
const vpcId = networkingRef.getOutput('vpcId')
const projectName = pulumi.getProject()
const stackName = pulumi.getStack()

// Preparing for EKS

// Export for kubeconfig

export class EKSCluster {

	static current_cluster_name = <string>clusterName;

	static eksAdmins = users;

	static managedPolicyArns: string[] = [
		'arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy',
		'arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy',
		'arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly',
		'arn:aws:iam::aws:policy/AmazonS3FullAccess',
		'arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy',
	];

	static async init(): Promise<eks.Cluster> {
		const config = new pulumi.Config();

		// SecurityGroup

		if (env === 'prod') {

	
			const sg = new aws.ec2.SecurityGroup(EKSCluster.current_cluster_name + '-sg', { name: "eks-sg-aa75a93", vpcId: "vpc-a699eec3" }, {
					protect: true,
				});

			const http_access = new aws.ec2.SecurityGroupRule("http_access", {
				cidrBlocks: ["0.0.0.0/0"],
				description: "allow http access",
				fromPort: 80,
				protocol: "tcp",
				securityGroupId: sg.id,
				toPort: 80,
				type: "ingress",
			}, {
				protect: true,
			});
			const https_access = new aws.ec2.SecurityGroupRule("https_access", {
				cidrBlocks: ["0.0.0.0/0"],
				description: "allow https access",
				fromPort: 443,
				protocol: "tcp",
				securityGroupId: sg.id,
				toPort: 443,
				type: "ingress",
			}, {
				protect: true,
			});

		}

		async function createRole(name: string): Promise<aws.iam.Role> {
			const role = new aws.iam.Role(name, {
				assumeRolePolicy: aws.iam.assumeRolePolicyForPrincipal({
					Service: 'ec2.amazonaws.com',
				}),
			});

			let counter = 0;
			for (const policy of EKSCluster.managedPolicyArns) {
				// Create RolePolicyAttachment without returning it.
				const rpa = new aws.iam.RolePolicyAttachment(`${name}-policy+${counter++}`, { policyArn: policy, role: role });
			}

			return role;
		}

		// Now create the roles and instance profiles for the worker group(s).
		const workerNodeInstanceRole1 = await createRole(EKSCluster.current_cluster_name + '-cluster-role1');
		const instanceProfile1 = new aws.iam.InstanceProfile('cluster-role-1', {
			role: workerNodeInstanceRole1,
			tags: {
				'pulumi:Project': projectName,
				'pulumi:Stack': stackName,
			},
		});

		// Mapping users for eks.ClusterOptions.
		let userMappings: UserMapping[] = [];
		for (let adminNames of EKSCluster.eksAdmins) {
			const iamUser = await aws.iam.getUser({
				userName: adminNames,
			});

			let userMapping: UserMapping = {
				groups: ['system:masters'],
				userArn: iamUser.arn,
				username: adminNames,
			};

			userMappings.push(userMapping);
		}


		let clusterCfg: eks.ClusterOptions = {
			name: EKSCluster.current_cluster_name,
			tags: {
				'pulumi:Project': projectName,
				'pulumi:Stack': stackName,
			},
			vpcId: vpcId,
			publicSubnetIds: (await publicSubnetIds).value,
			privateSubnetIds: (await privateSubnetIds).value,
			skipDefaultNodeGroup: true,
			instanceRoles: [workerNodeInstanceRole1],
			createOidcProvider: true,
			userMappings: userMappings,
			version: eksVersion,
		};

		const cluster = new eks.Cluster(EKSCluster.current_cluster_name + '-cluster', clusterCfg);

		//this.kubeconfig = cluster.kubeconfig;

		// Grant cluster admin access to all admins with k8s ClusterRole and ClusterRoleBinding
		const ClusterRoleBinding = new k8s.rbac.v1.ClusterRole(
			'clusterAdminUsers',
			{
				metadata: {
					name: 'clusterAdminUsers',
				},
				rules: [
					{
						apiGroups: ['*'],
						resources: ['*'],
						verbs: ['*'],
					},
				],
			},
			{ provider: cluster.provider },
		);

		// Mapping users for k8s.rbac.v1.ClusterRoleBinding
		let clusterAdmins: any[] = [];
		for (let adminNames of EKSCluster.eksAdmins) {
			clusterAdmins.push({
				kind: 'User',
				name: adminNames,
			});
		}

		let ClusterRoleBindingCfg: k8s.rbac.v1.ClusterRoleBindingArgs = {
			metadata: {
				name: 'cluster-admin-binding',
			},
			subjects: clusterAdmins,
			roleRef: {
				kind: 'ClusterRole',
				name: 'clusterAdminUsers',
				apiGroup: 'rbac.authorization.k8s.io',
			},
		};

		const bindClusterRole = new k8s.rbac.v1.ClusterRoleBinding('cluster-admin-binding', ClusterRoleBindingCfg, {
			provider: cluster.provider,
		});
		const customRolePolicyId = '-90eb1c99'
		const eksRolePolicyName = `${clusterName}-cluster-eksRole${env === "prod" ? customRolePolicyId : ''}`
		const eksClusterRoleId = '-6dd8b91'
		const eksClusterRoleName = `${clusterName}-cluster-eksRole-role${env === "prod" ? eksClusterRoleId : ''}`

		const eks_cluster_role = new aws.iam.Role(`${clusterName}-cluster-role`, {
			assumeRolePolicy: "{\"Statement\":[{\"Action\":\"sts:AssumeRole\",\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"eks.amazonaws.com\"}}],\"Version\":\"2012-10-17\"}",
			description: "Allows EKS to manage clusters on your behalf.",
			managedPolicyArns: [
				"arn:aws:iam::aws:policy/AmazonEKSClusterPolicy",
				"arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
			],
			name: eksClusterRoleName,
		}, {
			protect: true,
		});
		const eksRolePolicyAttachment = new aws.iam.RolePolicyAttachment(eksRolePolicyName, {
			role: eks_cluster_role,
			policyArn: 'arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy',
		});

		// Import Existing Node Group
		const eks_ng = new aws.eks.NodeGroup(`${clusterName}-ng`, {
			amiType: "AL2_x86_64",
			capacityType: "ON_DEMAND",
			clusterName: EKSCluster.current_cluster_name,
			diskSize: Number(nodeDiskSize),
			instanceTypes: [nodeInstanceTypes],
			nodeGroupName: EKSCluster.current_cluster_name + '-ng1',
			nodeRoleArn: workerNodeInstanceRole1.arn,
			scalingConfig: {
				desiredSize: Number(nodeDesiredSize),
				minSize: Number(nodeMinSize),
				maxSize: Number(nodeMaxSize)
			},
			subnetIds: [
				...(await publicSubnetIds).value,
				...(await privateSubnetIds).value,
			],
			tags: {
				"pulumi:Project": `${clusterName}`,
				"pulumi:Stack": env,
			},
			updateConfig: {
				maxUnavailable: 1,
			},
			version: eksVersion,
		}, {
			protect: true,
			dependsOn: [cluster]
		});

		// --------------------

		// Add-ons
		const awsEbsCsiDriver = new aws.eks.Addon("awsEbsCsiDriver", {
			clusterName: cluster.eksCluster.name,
			addonName: "aws-ebs-csi-driver",
		});

		// --------------------

		// EKS essentials - Cert Manager
		// Create a namespace for cert-manager.
		const cert_manager_ns = 'cert-manager';
		const cert_manager_namespace = new k8s.core.v1.Namespace(
			'cert-manager-ns',
			{
				metadata: {
					name: cert_manager_ns,
					labels: {
						name: cert_manager_ns,
					},
				},
			}, {
			provider: cluster.provider,
			dependsOn: [eks_ng]
		},);

		const cert_manager = new certmanager.CertManager(
			'cert-manager',
			{
				installCRDs: true,
				helmOptions: {
					namespace: cert_manager_ns,
				},
			}, {
			provider: cluster.provider,
			dependsOn: [cert_manager_namespace]
		},);

		// --------------------

		// EKS essentials - Metrics Server

		// Create a namespace for metrics-server.
		const metrics_server_ns = 'metrics-server';
		const metrics_server_namespace = new k8s.core.v1.Namespace(
			'metrics-server-ns',
			{
				metadata: {
					name: metrics_server_ns,
					labels: {
						name: metrics_server_ns,
					},
				},
			}, {
			provider: cluster.provider,
			dependsOn: [eks_ng]
		},);

		const k8sMetrics = new k8s.helm.v3.Release(
			'metrics-server',
			{
				chart: 'metrics-server',
				version: '3.8.2',
				namespace: 'metrics-server',
				repositoryOpts: {
					repo: 'https://kubernetes-sigs.github.io/metrics-server',
				},
			}, {
			provider: cluster.provider,
			dependsOn: [metrics_server_namespace]
		},);

		// --------------------

		// EKS essentials - AWS Load Balancer Controller

		// Export for AWS Load Balancer Controller
		let clusterNodeInstanceRoleName = cluster.instanceRoles.apply((roles) => roles[0].name);

		// Create IAM Policy for the IngressController called "ingressController-iam-policy” and read the policy ARN.
		const ingressControllerPolicy = new aws.iam.Policy('ingressController-iam-policy', {
			policy: JSON.stringify(ingressControllerPolicyConfig),
		});

		// Attach this policy to the NodeInstanceRole of the worker nodes
		const nodeinstanceRole = new aws.iam.RolePolicyAttachment('eks-NodeInstanceRole-policy-attach', {
			policyArn: ingressControllerPolicy.arn,
			role: clusterNodeInstanceRoleName,
		});

		// Install AWS Load Balancer Controller via Helm chart
		const awslbcontroller = new k8s.helm.v3.Release(
			'aws-load-balancer-controller',
			{
				chart: 'aws-load-balancer-controller',
				version: helmChartVersion,
				namespace: 'kube-system',
				repositoryOpts: {
					repo: 'https://aws.github.io/eks-charts',
				},
				values: {
					clusterName: cluster.eksCluster.name,
					enableCertManager: true,
				},
			}, {
			provider: cluster.provider,
			dependsOn: [cert_manager]
		},);

		// --------------------

		// app - CloudWatch + Fluent Bit
		// cloudwatch-logging - create a namespace
		const cloudwatch_logging = 'amazon-cloudwatch';
		const cloudwatch_logging_ns = `${cloudwatch_logging}-ns`;
		const cloudwatch_logging_namespace = new k8s.core.v1.Namespace(
			cloudwatch_logging_ns,
			{
				metadata: {
					name: `${cloudwatch_logging}`,
					labels: {
						name: `${cloudwatch_logging}`,
					},
				},
			}, {
			provider: cluster.provider,
			dependsOn: [eks_ng]
		},);

		// cloudwatch-logging - cluster info configmap
		const cloudwatch_clusterInfo_configMap = new k8s.core.v1.ConfigMap(
			`${cloudwatch_logging}-clusterInfo-configMap`,
			{
				"metadata": {
					"name": "fluent-bit-cluster-info",
					"namespace": "amazon-cloudwatch",
				},
				"data": {
					"cluster.name": EKSCluster.current_cluster_name,
					"http.port": "2020",
					"http.server": "On",
					"logs.region": region,
					"read.head": "Off",
					"read.tail": "On"
				}
			}, {
			provider: cluster.provider,
			dependsOn: [eks_ng]
		},);
		const cloudwatch_serviceAccount = new k8s.core.v1.ServiceAccount(
			`${cloudwatch_logging}-serviceAccount`,
			{
				"metadata": {
					"name": "fluent-bit",
					"namespace": "amazon-cloudwatch"
				}
			}, {
			provider: cluster.provider,
			dependsOn: [cloudwatch_logging_namespace]
		},);
		const cloudwatch_clusterRole = new k8s.rbac.v1.ClusterRole(
			`${cloudwatch_logging}-clusterRole`,
			cloudwatchClusterRole, {
			provider: cluster.provider,
			dependsOn: [cloudwatch_logging_namespace]
		},);

		const cloudwatch_clusterRoleBinding = new k8s.rbac.v1.ClusterRoleBinding(
			`${cloudwatch_logging}-clusterRoleBinding`,
			cloudwatchClusterRoleBinding, {
			provider: cluster.provider,
			dependsOn: [cloudwatch_logging_namespace]
		},);

		const cloudwatch_clusterDeamonSet = new k8s.apps.v1.DaemonSet(
			`${cloudwatch_logging}-DaemonSet`,
			cloudwatchDaemonSet , {
			provider: cluster.provider,
			dependsOn: [cloudwatch_logging_namespace]
		},);

		const cloudwatch_configMap = new k8s.core.v1.ConfigMap(
			`${cloudwatch_logging}-configMap`,
			cloudwatchconfigmap, {
			provider: cluster.provider,
			dependsOn: [cloudwatch_logging_namespace]
		},);


		// --------------------

		// namespace for services
		const cloudDevNamespaceName = `cloud-${env}`;
		const cloudDevNamespace = new k8s.core.v1.Namespace(
			cloudDevNamespaceName,
			{
				metadata: { name: cloudDevNamespaceName },
			}, {
			provider: cluster.provider,
			dependsOn: [eks_ng]
		},);

		return cluster;
	}
}
