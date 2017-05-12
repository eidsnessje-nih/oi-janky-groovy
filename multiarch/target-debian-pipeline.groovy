// properties are set via "generate-pipeline.groovy" (jobDsl)

// we can't use "load()" here because we don't have a file context (or a real checkout of "oi-janky-groovy" -- the pipeline plugin hides that checkout from the actual pipeline execution)
def vars = fileLoader.fromGit(
	'multiarch/vars.groovy', // script
	'https://github.com/docker-library/oi-janky-groovy.git', // repo
	'master', // branch
	null, // credentialsId
	'master', // node/label
)

// setup environment variables, etc.
vars.prebuildSetup(this)

env.DPKG_ARCH = vars.dpkgArches[env.ACT_ON_ARCH]
if (!env.DPKG_ARCH) {
	error("Unknown 'dpkg' architecture for '${env.ACT_ON_ARCH}'.")
}

node(vars.node(env.ACT_ON_ARCH, env.ACT_ON_IMAGE)) {
	env.BASHBREW_CACHE = env.WORKSPACE + '/bashbrew-cache'
	env.BASHBREW_LIBRARY = env.WORKSPACE + '/oi/library'

	stage('Checkout') {
		checkout(
			poll: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/tianon/docker-brew-debian.git',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '*/master']],
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'brew',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
		checkout(
			poll: false,
			changelog: true,
			scm: [
				$class: 'GitSCM',
				userRemoteConfigs: [[
					url: 'https://github.com/docker/docker.git',
					name: 'origin',
					refspec: '+refs/heads/master:refs/remotes/origin/master',
				]],
				branches: [[name: '68a5336b61c8b252966b582f0e4d08c6ae0bdb63']], // https://github.com/moby/moby/tree/master/contrib  ||  https://github.com/moby/moby/commits/master/contrib
				extensions: [
					[
						$class: 'CloneOption',
						honorRefspec: true,
						noTags: true,
					],
					[
						$class: 'CleanCheckout',
					],
					[
						$class: 'RelativeTargetDirectory',
						relativeTargetDir: 'docker',
					],
				],
				doGenerateSubmoduleConfigurations: false,
				submoduleCfg: [],
			],
		)
	}

	ansiColor('xterm') {
		dir('brew') {
			stage('Prep') {
				sh '''
					echo "$DPKG_ARCH" > arch
					echo "$TARGET_NAMESPACE/$ACT_ON_IMAGE" > repo

					ln -svfT ../docker/contrib/mkimage.sh ./mkimage.sh
				'''
			}

			// TODO parallelize?  pre-build artifacts elsewhere which are then consumed here?  (as in Ubuntu)
			stage('Update') {
				retry(3) {
					sh '''
						./update.sh
					'''
				}
			}

			stage('Commit') {
				sh '''
					git config user.name 'Docker Library Bot'
					git config user.email 'github+dockerlibrarybot@infosiftr.com'

					git add -A .
					git commit -m "Build for $ACT_ON_ARCH"
				'''
			}
			vars.seedCache(this)

			vars.generateStackbrewLibrary(this)
		}

		vars.createFakeBashbrew(this)
		vars.bashbrewBuildAndPush(this)

		vars.stashBashbrewBits(this)
	}
}

vars.docsBuildAndPush(this)
