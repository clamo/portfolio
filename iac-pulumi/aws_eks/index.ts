import { EKSCluster } from './EKSCluster';

export = async () => {
	// create resources
	return { out: await EKSCluster.init() };
};
