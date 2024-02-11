import * as pulumi from '@pulumi/pulumi';
import * as aws from '@pulumi/aws';
import * as awsx from '@pulumi/awsx';
import * as eks from '@pulumi/eks';
import { UserMapping } from '@pulumi/eks';
import * as k8s from '@pulumi/kubernetes';
import * as certmanager from '@pulumi/kubernetes-cert-manager';


// --------------------
const cfg = new pulumi.Config();
const users: Array<string> = cfg.requireObject('users') as any;
const clusterName = <string> cfg.require('clusterName');
// Preparing for EKS

// Export for kubeconfig

export class EKSCluster {
	static current_cluster_name = <string> clusterName;

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

		// EKS essentials - Initialization
		// Create a VPC for our cluster.
		const vpc = new awsx.ec2.Vpc(EKSCluster.current_cluster_name + '-vpc', {
			tags: {
				Name: EKSCluster.current_cluster_name + '-vpc',
				'pulumi:Project': pulumi.getProject(),
				'pulumi:Stack': pulumi.getStack(),
			},
			cidrBlock: '10.0.0.0/16',
			numberOfAvailabilityZones: 3,
			subnets: [
				{
					type: 'public',
					tags: {
						'kubernetes.io/role/elb': '1',
					},
				},
				{
					type: 'private',
					tags: {
						'kubernetes.io/role/internal-elb': '1',
					},
				},
			],
		});

		const sg = new awsx.ec2.SecurityGroup(EKSCluster.current_cluster_name + '-sg', { vpc });
		awsx.ec2.SecurityGroupRule.ingress(
			'https-access',
			sg,
			new awsx.ec2.AnyIPv4Location(),
			new awsx.ec2.TcpPorts(443),
			'allow https access',
		);
		awsx.ec2.SecurityGroupRule.ingress(
			'http-access',
			sg,
			new awsx.ec2.AnyIPv4Location(),
			new awsx.ec2.TcpPorts(80),
			'allow http access',
		);

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
				'pulumi:Project': pulumi.getProject(),
				'pulumi:Stack': pulumi.getStack(),
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
				'pulumi:Project': pulumi.getProject(),
				'pulumi:Stack': pulumi.getStack(),
			},
			vpcId: vpc.id,
			publicSubnetIds: vpc.publicSubnetIds,
			privateSubnetIds: vpc.privateSubnetIds,
			skipDefaultNodeGroup: true,
			instanceRoles: [workerNodeInstanceRole1],
			createOidcProvider: true,
			userMappings: userMappings,
			version: "1.24",
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

		// Create a simple AWS managed node group using a cluster as input.
		const managedNodeGroup = eks.createManagedNodeGroup(EKSCluster.current_cluster_name + '-ng', {
			cluster: cluster,
			nodeGroupName: EKSCluster.current_cluster_name + '-ng1',
			nodeRoleArn: workerNodeInstanceRole1.arn,
			capacityType: 'ON_DEMAND',
			instanceTypes: ['m6i.large'],
			scalingConfig: {
				desiredSize: 3,
				minSize: 3,
				maxSize: 6,
			},
			diskSize: 81,
			tags: {
				'pulumi:Project': pulumi.getProject(),
				'pulumi:Stack': pulumi.getStack(),
			},
			version: "1.24",
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
			},
			{ provider: cluster.provider },
		);

		const manager = new certmanager.CertManager(
			'cert-manager',
			{
				installCRDs: true,
				helmOptions: {
					namespace: cert_manager_ns,
				},
			},
			{ provider: cluster.provider },
		);

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
			},
			{ provider: cluster.provider },
		);

		const k8sMetrics = new k8s.helm.v3.Release(
			'metrics-server',
			{
				chart: 'metrics-server',
				version: '3.7.0',
				namespace: 'metrics-server',
				repositoryOpts: {
					repo: 'https://kubernetes-sigs.github.io/metrics-server',
				},
			},
			{ provider: cluster.provider },
		);

		// --------------------

		// EKS essentials - AWS Load Balancer Controller

		// Export for AWS Load Balancer Controller
		let clusterNodeInstanceRoleName = cluster.instanceRoles.apply((roles) => roles[0].name);

		// Create IAM Policy for the IngressController called "ingressController-iam-policy” and read the policy ARN.
		const ingressControllerPolicy = new aws.iam.Policy('ingressController-iam-policy', {
			policy: {
				Version: '2012-10-17',
				Statement: [
					{
						Effect: 'Allow',
						Action: 'iam:CreateServiceLinkedRole',
						Resource: '*',
						Condition: {
							StringEquals: {
								'iam:AWSServiceName': 'elasticloadbalancing.amazonaws.com',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: [
							'ec2:DescribeAccountAttributes',
							'ec2:DescribeAddresses',
							'ec2:DescribeAvailabilityZones',
							'ec2:DescribeInternetGateways',
							'ec2:DescribeVpcs',
							'ec2:DescribeVpcPeeringConnections',
							'ec2:DescribeSubnets',
							'ec2:DescribeSecurityGroups',
							'ec2:DescribeInstances',
							'ec2:DescribeNetworkInterfaces',
							'ec2:DescribeTags',
							'ec2:GetCoipPoolUsage',
							'ec2:DescribeCoipPools',
							'elasticloadbalancing:DescribeLoadBalancers',
							'elasticloadbalancing:DescribeLoadBalancerAttributes',
							'elasticloadbalancing:DescribeListeners',
							'elasticloadbalancing:DescribeListenerCertificates',
							'elasticloadbalancing:DescribeSSLPolicies',
							'elasticloadbalancing:DescribeRules',
							'elasticloadbalancing:DescribeTargetGroups',
							'elasticloadbalancing:DescribeTargetGroupAttributes',
							'elasticloadbalancing:DescribeTargetHealth',
							'elasticloadbalancing:DescribeTags',
						],
						Resource: '*',
					},
					{
						Effect: 'Allow',
						Action: [
							'cognito-idp:DescribeUserPoolClient',
							'acm:ListCertificates',
							'acm:DescribeCertificate',
							'iam:ListServerCertificates',
							'iam:GetServerCertificate',
							'waf-regional:GetWebACL',
							'waf-regional:GetWebACLForResource',
							'waf-regional:AssociateWebACL',
							'waf-regional:DisassociateWebACL',
							'wafv2:GetWebACL',
							'wafv2:GetWebACLForResource',
							'wafv2:AssociateWebACL',
							'wafv2:DisassociateWebACL',
							'shield:GetSubscriptionState',
							'shield:DescribeProtection',
							'shield:CreateProtection',
							'shield:DeleteProtection',
						],
						Resource: '*',
					},
					{
						Effect: 'Allow',
						Action: ['ec2:AuthorizeSecurityGroupIngress', 'ec2:RevokeSecurityGroupIngress'],
						Resource: '*',
					},
					{
						Effect: 'Allow',
						Action: ['ec2:CreateSecurityGroup'],
						Resource: '*',
					},
					{
						Effect: 'Allow',
						Action: ['ec2:CreateTags'],
						Resource: 'arn:aws:ec2:*:*:security-group/*',
						Condition: {
							StringEquals: {
								'ec2:CreateAction': 'CreateSecurityGroup',
							},
							Null: {
								'aws:RequestTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: ['ec2:CreateTags', 'ec2:DeleteTags'],
						Resource: 'arn:aws:ec2:*:*:security-group/*',
						Condition: {
							Null: {
								'aws:RequestTag/elbv2.k8s.aws/cluster': 'true',
								'aws:ResourceTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: ['ec2:AuthorizeSecurityGroupIngress', 'ec2:RevokeSecurityGroupIngress', 'ec2:DeleteSecurityGroup'],
						Resource: '*',
						Condition: {
							Null: {
								'aws:ResourceTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: ['elasticloadbalancing:CreateLoadBalancer', 'elasticloadbalancing:CreateTargetGroup'],
						Resource: '*',
						Condition: {
							Null: {
								'aws:RequestTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: [
							'elasticloadbalancing:CreateListener',
							'elasticloadbalancing:DeleteListener',
							'elasticloadbalancing:CreateRule',
							'elasticloadbalancing:DeleteRule',
						],
						Resource: '*',
					},
					{
						Effect: 'Allow',
						Action: ['elasticloadbalancing:AddTags', 'elasticloadbalancing:RemoveTags'],
						Resource: [
							'arn:aws:elasticloadbalancing:*:*:targetgroup/*/*',
							'arn:aws:elasticloadbalancing:*:*:loadbalancer/net/*/*',
							'arn:aws:elasticloadbalancing:*:*:loadbalancer/app/*/*',
						],
						Condition: {
							Null: {
								'aws:RequestTag/elbv2.k8s.aws/cluster': 'true',
								'aws:ResourceTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: ['elasticloadbalancing:AddTags', 'elasticloadbalancing:RemoveTags'],
						Resource: [
							'arn:aws:elasticloadbalancing:*:*:listener/net/*/*/*',
							'arn:aws:elasticloadbalancing:*:*:listener/app/*/*/*',
							'arn:aws:elasticloadbalancing:*:*:listener-rule/net/*/*/*',
							'arn:aws:elasticloadbalancing:*:*:listener-rule/app/*/*/*',
						],
					},
					{
						Effect: 'Allow',
						Action: [
							'elasticloadbalancing:ModifyLoadBalancerAttributes',
							'elasticloadbalancing:SetIpAddressType',
							'elasticloadbalancing:SetSecurityGroups',
							'elasticloadbalancing:SetSubnets',
							'elasticloadbalancing:DeleteLoadBalancer',
							'elasticloadbalancing:ModifyTargetGroup',
							'elasticloadbalancing:ModifyTargetGroupAttributes',
							'elasticloadbalancing:DeleteTargetGroup',
						],
						Resource: '*',
						Condition: {
							Null: {
								'aws:ResourceTag/elbv2.k8s.aws/cluster': 'false',
							},
						},
					},
					{
						Effect: 'Allow',
						Action: ['elasticloadbalancing:RegisterTargets', 'elasticloadbalancing:DeregisterTargets'],
						Resource: 'arn:aws:elasticloadbalancing:*:*:targetgroup/*/*',
					},
					{
						Effect: 'Allow',
						Action: [
							'elasticloadbalancing:SetWebAcl',
							'elasticloadbalancing:ModifyListener',
							'elasticloadbalancing:AddListenerCertificates',
							'elasticloadbalancing:RemoveListenerCertificates',
							'elasticloadbalancing:ModifyRule',
						],
						Resource: '*',
					},
				],
			},
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
				version: '1.3.3',
				namespace: 'kube-system',
				repositoryOpts: {
					repo: 'https://aws.github.io/eks-charts',
				},
				values: {
					clusterName: cluster.eksCluster.name,
					enableCertManager: true,
				},
			},
			{ provider: cluster.provider },
		);

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
			},
			{ provider: cluster.provider },
		);

		// cloudwatch-logging - import yaml
		const cloudwatch_clusterInfo = new k8s.yaml.ConfigFile(
			`${cloudwatch_logging}-clusterInfo`,
			{
				file: `./apps/${cloudwatch_logging}/cluster-info.yaml`,
			},
			{ provider: cluster.provider },
		);
		const cloudwatch_configMap = new k8s.yaml.ConfigFile(
			`${cloudwatch_logging}-configMap`,
			{
				file: `./apps/${cloudwatch_logging}/${cloudwatch_logging}.yaml`,
			},
			{ provider: cluster.provider },
		);


		// namespace for services
		const cloudDevNamespaceName = `cloud-dev`;
		const cloudDevNamespace = new k8s.core.v1.Namespace(
			cloudDevNamespaceName,
			{
				metadata: { name: cloudDevNamespaceName },
			},
			{ provider: cluster.provider },
		);

		const cloudStageNamespaceName = `cloud-stage`;
		const cloudStageNamespace = new k8s.core.v1.Namespace(
			cloudStageNamespaceName,
			{
				metadata: { name: cloudStageNamespaceName },
			},
			{ provider: cluster.provider },
		);


		return cluster;
	}
}
