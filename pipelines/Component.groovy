package cicd.pipelines

import javaposse.jobdsl.dsl.Job
import javaposse.jobdsl.dsl.Folder

class Component {
    /** Name of the component */
    String name

    /** Git URL */
    String git_url

    /** Git branch */
    String git_branch = 'master'

    /** Job to build the component */
    Job build

    /** Job to analyze the component */
    Job analyze

    /** Job to integration test the component */
    Job integration_test

    /** Names of jobs to use as upstreams */
    List<String> upstreams

    void configure(def dslFactory, String project_name, Folder project_folder) {
        dslFactory.with {
            def component_folder = folder("${project_folder.name}/Build/${name}")

            def build_job = build ?: job("build.${project_name}.${name}")
            build_job.with {
                deliveryPipelineConfiguration('Build', 'Actual Build')
                parameters {
                    stringParam('commit', git_branch, 'Commit/Branch/Tag to build.')
                }
                scm {
                    git(git_url, git_branch)
                }
            }

            job("${component_folder.name}/build") {
                deliveryPipelineConfiguration('Build', 'C-I Build')
                scm {
                    git(git_url, git_branch)
                }
                steps {
                    downstreamParameterized {
                        trigger(build_job.name) {
                            block {
                                buildStepFailure('FAILURE')
                                failure('FAILURE')
                                unstable('UNSTABLE')
                            }
                            parameters {
                                currentBuild()
                            }
                        }
                    }
                }
                triggers {
                    scm('H/2 * * * *')
                    upstreams?.each { upstream_job ->
                        upstream(upstream_job)
                    }
                }
                publishers {
                    downstreamParameterized {
                        trigger("${component_folder.name}/integration_test") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_${build_job.name.replaceAll('[^a-zA-Z0-9]+', '_')}}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                        trigger("${component_folder.name}/analyze") {
                            condition('UNSTABLE_OR_BETTER')
                            parameters {
                                predefinedProp('commit', '${GIT_COMMIT}')
                                predefinedProp('build_job_build_number', "\${TRIGGERED_BUILD_NUMBER_${build_job.name.replaceAll('[^a-zA-Z0-9]+', '_')}}")
                                predefinedProp('previous_commit', '${GIT_PREVIOUS_SUCCESSFUL_COMMIT}')
                            }
                        }
                    }
                }
            }

            def integration_test_job = integration_test ?: job('integration_test')
            integration_test_job.with {
                name = "${component_folder.name}/integration_test"
                deliveryPipelineConfiguration('Build', 'Integration Test')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered integration test.')
                    stringParam('commit', git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
                publishers {
                    downstreamParameterized {
                        trigger("${project_folder.name}/prepare_release") {
                            condition('SUCCESS')
                            parameters {
                                predefinedProp("${name}_previous_commit", '${previous_commit}')
                                predefinedProp("${name}_commit", '${commit}')
                                predefinedProp("${name}_build_number", '${build_job_build_number}')
                            }
                        }
                    }
                }
            }

            def analyze_job = analyze ?: job('analyze')
            analyze_job.with {
                name = "${component_folder.name}/analyze"
                deliveryPipelineConfiguration('Build', 'Analyze')
                parameters {
                    stringParam('build_job_build_number', null, 'Build number of build that triggered analysis.')
                    stringParam('commit', git_branch, 'Commit to analyze.')
                    stringParam('previous_commit', null, 'Reference of previous successful build.')
                }
                scm {
                    git(git_url, '${commit}')
                }
            }
        }
    }
}
