properties(
    [
        buildDiscarder(logRotator(numToKeepStr: '20')),
        parameters(
            [   
                // Common Parameters
                separator(name: "common-parameters", sectionHeader: "Common Parameters"),
                string(name: 'PREFIX', defaultValue: 'lx', description: 'Host name prefix'),
                string(name: 'OPCO_NUMBER', defaultValue: '739', description: 'Opco Number'),
                string(name: 'SUFFIX', defaultValue: 'q11', description: 'Host name suffix'),

                // Terraform & Cheff Parameters
                separator(name: "terraform-chef-parameters", sectionHeader: "Terraform & Cheff Paramaters"),
                string(name: 'DB_SNAPSHOT_IDENTIFIER', defaultValue: 'lx739q9-db-final-snapshot', description: 'DB Snapshot identifier'),
                string(name: 'DB_INSTANCE_TYPE', defaultValue: 'db.t3.medium', description: 'DB Instance Type'),
                string(name: 'TIMEZONE', defaultValue: 'America/Chicago', description: 'TimeZone'),
                string(name: 'opco_desc', description: 'The short description of this opco instance',  trim: true),

                // SWMS Opco Deployment Parameters
                separator(name: "swms-opco-deployment", sectionHeader: "SWMS Opco Deployment Parameters"),
                [
                    name: 'artifact_s3_bucket',
                    description: 'The build\'s targeted platform',
                    $class: 'ChoiceParameter',
                    choiceType: 'PT_SINGLE_SELECT',
                    filterLength: 1,
                    filterable: false,
                    randomName: 'choice-parameter-289390844205293',
                    script: [
                        $class: 'GroovyScript',
                        script: [classpath: [], sandbox: false, script: '''\
                            return [
                                \'swms-build-artifacts\',
                                \'swms-build-dev-artifacts\'
                            ]'''.stripIndent()
                        ]
                    ]
                ],
                [
                    name: 'platform',
                    description: 'The build\'s targeted platform',
                    $class: 'ChoiceParameter',
                    choiceType: 'PT_SINGLE_SELECT',
                    filterLength: 1,
                    filterable: false,
                    randomName: 'choice-parameter-289390844205293',
                    script: [
                        $class: 'GroovyScript',
                        script: [classpath: [], sandbox: false, script: '''\
                            return [
                                \'aix_11g_11g\',
                                \'aix_19c_12c\',
                                \'linux\'
                            ]'''.stripIndent()
                        ]
                    ]
                ],
                string(name: 'artifact_version', description: 'The swms version to deploy', trim: true),
                [
                    name: 'artifact_name',
                    description: 'The name of the artifact to deploy',
                    $class: 'CascadeChoiceParameter',
                    choiceType: 'PT_SINGLE_SELECT',
                    filterLength: 1,
                    filterable: false,
                    randomName: 'choice-parameter-artifact_name',
                    referencedParameters: 'artifact_s3_bucket, platform, artifact_version',
                    script: [
                        $class: 'GroovyScript',
                        script: [classpath: [], sandbox: false, script: '''\
                                if (platform?.trim() && artifact_version?.trim()) {
                                    def process = "aws s3api list-objects --bucket ${artifact_s3_bucket} --prefix ${platform}-${artifact_version} --query Contents[].Key".execute()
                                    return process.text.replaceAll('"', "").replaceAll("\\n","").replaceAll(" ","").tokenize(',[]')
                                } else {
                                    return []
                                }
                            '''.stripIndent()
                        ]
                    ]
                ],
                string(name: 'dba_masterfile_names', description: 'Comma seperated names of the Privileged master files to apply to the current database. Will not run if left blank. Ran before the master_file', defaultValue: '', trim: true),
                string(name: 'master_file_retry_count', description: 'Amount of attempts to apply the master file. This is setup to handle circular dependencies by running the same master file multiple times.', defaultValue: '3', trim: true),
                
                // Data Migration Parameters
                separator(name: "data-migration", sectionHeader: "Data Migration Parameters"),
                string(name: 'SOURCE_DB', defaultValue: 'rsxxxe', description: 'Source Database. eg: rs040e'),
            ]
        )
    ]
)
pipeline {
    agent { label 'master' }
    environment {
        S3_ACCESS_ARN="arn:aws:iam::546397704060:role/ec2_s3_role";
    }
    stages {
        stage('Verifying parameters') {
            steps {
                echo "Section: Verifying parameters"
                sh """
                    echo "Source DB: ${params.SOURCE_DB}"
                    echo "Target DB: ${params.TARGET_DB}"
                    if [ "`echo ${params.SOURCE_DB} | cut -c6`" = "e" ]; then
                    echo Good This is E box ${params.SOURCE_DB}
                    else
                    echo Error: Please use rsxxxe
                    exit
                    fi 
                """
            }
        }
        stage("AWS RDS Deploymnet") {
            steps {
                echo "Section: AWS RDS Deploymnet"
                script {
                    try {
                        build job: "swms-rds-deployment-terraform", parameters: [
                            string(name: 'TERRAFORM_COMMAND', value: "create"),
                            string(name: 'PREFIX', value: "${params.PREFIX}"),
                            string(name: 'SUFFIX', value: "${params.SUFFIX}"),
                            string(name: 'OPCO_NUMBER', value: "${params.OPCO_NUMBER}"),
                            string(name: 'DB_SNAPSHOT_IDENTIFIER', value: "${params.DB_SNAPSHOT_IDENTIFIER}"),
                            string(name: 'DB_INSTANCE_TYPE', value: "${params.DB_INSTANCE_TYPE}"),
                            string(name: 'TIMEZONE', value: "${params.TIMEZONE}")
                        ]
                        echo "EC2 & RDS Intances provsioning successfull!"
                    } catch (e) {
                        echo "EC2 & RDS Intances provsioning failed!"
                        throw e
                    }
                }
            }
        }
        stage("Cheff Configuration create") {
            steps {
                echo "Section: Cheff Configuration create"
                script {
                    catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
                        build job: "ashblk-swms-infra-aws-chef", parameters: [
                            string(name: 'opco_num', value: "${params.OPCO_NUMBER}"),
                            string(name: 'opco_desc', value: "${params.opco_desc}"),
                            string(name: 'opco_type', value: "${params.SUFFIX}"),
                            string(name: 'opco_tz', value: "${params.TIMEZONE}"),
                            string(name: 'rds_url', value: "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}-db.swms-np.us-east-1.aws.sysco.net"),
                            string(name: 'inst_type', value: "t3.medium"),
                            string(name: "kitchen_cmd", value: "converge")
                        ]
                    }
                }
            }
        }
        stage("Cheff Configuration converge") {
            steps {
                echo "Section: Cheff Configuration converge"
                script {
                    try {
                        build job: "ashblk-swms-infra-aws-chef", parameters: [
                            string(name: 'opco_num', value: "${params.OPCO_NUMBER}"),
                            string(name: 'opco_desc', value: "${params.opco_desc}"),
                            string(name: 'opco_type', value: "${params.SUFFIX}"),
                            string(name: 'opco_tz', value: "${params.TIMEZONE}"),
                            string(name: 'rds_url', value: "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}-db.swms-np.us-east-1.aws.sysco.net"),
                            string(name: 'inst_type', value: "t3.medium"),
                            string(name: "kitchen_cmd", value: "converge")
                        ]
                        echo "Cheff configuration Successful!"
                    } catch (e) {
                        echo "Cheff configuration Failed!"
                        throw e
                    }
                }
            }
        }
        stage("SWMS Opco Deploymnet") {
            steps {
                echo "Section: SWMS Opco Deploymnet"
                script {
                    try {
                        build job: "swms-opco-deployment", parameters: [
                            string(name: 'target_server_name', value: "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}.swms-np.us-east-1.aws.sysco.net"),
                            string(name: 'artifact_s3_bucket', value: "${params.artifact_s3_bucket}"),
                            string(name: 'platform', value: "${params.platform}"),
                            string(name: 'artifact_version', value: "${params.artifact_version}"),
                            string(name: 'artifact_name', value: "${params.artifact_name}"),
                            string(name: 'dba_masterfile_names', value: "${params.dba_masterfile_names}"),
                            string(name: 'master_file_retry_count', value: "${params.master_file_retry_count}")
                        ]
                        echo "SWMS Opco Deployment Successful!"
                    } catch (e) {
                        echo "SWMS Opco Deployment Failed!"
                        throw e
                    }
                }
            }
        }
        stage("SWMS Data Migration") {
            steps {
                echo "Section: SWMS Data Migration"
                script {
                    try {
                        build job: "swms-db-migrate-AIX-RDS-test", parameters: [
                            string(name: 'SOURCE_DB', value: "${params.SOURCE_DB}"),
                            string(name: 'TARGET_DB', value: "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}_db"),
                            string(name: 'ROOT_PW', value: ""),
                            string(name: 'TARGET_SERVER', value: "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}"),
                            string(name: 'artifact_s3_bucket', value: "${params.artifact_s3_bucket}"),
                            string(name: 'platform', value: "${params.platform}"),
                            string(name: 'artifact_version', value: "${params.artifact_version}"),
                            string(name: 'artifact_name', value: "${params.artifact_name}"),
                            string(name: 'dba_masterfile_names', value: "${params.dba_masterfile_names}"),
                            string(name: 'master_file_retry_count', value: "${params.master_file_retry_count}")
                        ]
                        echo "Data Migration Successful!"
                    } catch (e) {
                        echo "Data Migration Failed!"
                        throw e
                    }
                }
            }
        }  
        stage("PMC Configuration") {
            steps {
                echo "Section: PMC Configuration"
                script {
                    env.INSTANCE = "${params.PREFIX}${params.OPCO_NUMBER}${params.SUFFIX}"
                    def INSTANCE_ID = sh(script: "aws ec2 describe-instances --filters 'Name=tag:Name,Values=$INSTANCE' --query Reservations[*].Instances[*].[InstanceId] --output text --region us-east-1", returnStdout: true).trim()
                    sh "aws ec2 delete-tags --resources ${INSTANCE_ID} --tags Key='Automation:PMC',Value='Always On' --region us-east-1"
                }
            }
        }
    }
    post {
        success {
            script {
                echo 'Environment provisioning is successful!'
            }
        }
        failure {
            script {
                echo 'Environment provisioning is failed!'
            }
        }
    }
}
