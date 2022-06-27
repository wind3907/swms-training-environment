properties(
    [
        buildDiscarder(logRotator(numToKeepStr: '20')),
        parameters(
            [   
                // RDS Deployment Parameters
                separator(name: "swms-rds-deployment", sectionHeader: "SWMS RDS Deployment"),
                choice(name: 'TERRAFORM_COMMAND', choices: ['create','destroy'], description: 'Type of Terraform command that needs to be run'),
                string(name: 'PREFIX', defaultValue: 'lx', description: 'Host name prefix'),
                string(name: 'SUFFIX', defaultValue: 'q11', description: 'Host name suffix'),
                string(name: 'OPCO_NUMBER', defaultValue: '739', description: 'Opco Number'),
                string(name: 'DB_SNAPSHOT_IDENTIFIER', defaultValue: 'lx739q9-db-final-snapshot', description: 'DB Snapshot identifier'),
                string(name: 'DB_INSTANCE_TYPE', defaultValue: 'db.t3.medium', description: 'DB Instance Type'),
                string(name: 'TIMEZONE', defaultValue: 'America/Chicago', description: 'TimeZone'),
                
                // Cheff Configuration Parameters
                separator(name: "swms-infra-aws-chef", sectionHeader: "SWMS AWS Chef configurations"),
                string(name: 'opco_num', description: 'The 3 digit OPCO number',  trim: true),
                string(name: 'opco_desc', description: 'The short description of this opco instance',  trim: true),
                string(name: 'opco_type', description: 'The short suffix of the server name that describes the instance environment type',  trim: true),
                string(name: 'opco_tz', description: 'The timezone the instance is to be set to',  trim: true),
                string(name: 'rds_url', description: 'The the FQDN url for the RDS instance attached to this instance',  trim: true),
                string(name: 'inst_type', description: 'The type of ec2 instance to launch',  trim: true),
                choice(name: "kitchen_cmd", choices: ['create', 'converge', 'destroy']),

                
                // SWMS Opco Deployment Parameters
                separator(name: "swms-opco-deployment", sectionHeader: "SWMS Opco Deployment"),
                string(name: 'target_server_name', description: "The target server to deploy to. If the domain is not 'na.sysco.net' use the full address", defaultValue: ""),
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
                string(name: 'TARGET_DB', defaultValue: 'lx###trn', description: 'Target Database. eg: lx036trn'),
                string(name: 'ROOT_PW', defaultValue: 'SwmsRoot123', description: 'Root Password'),
                string(name: 'TARGET_SERVER', defaultValue: 'lx###trn', description: 'Host ec2 instance. eg: lx036trn')
            ]
        )
    ]
)
pipeline {
    agent { label 'master' }
    environment {
        SSH_KEY = credentials('/swms/jenkins/swms-universal-build/svc_swmsci_000/key')
        S3_ACCESS_ARN="arn:aws:iam::546397704060:role/ec2_s3_role";
        AWS_ROLE_SESSION_NAME="swms-data-migration";
        RDS_INSTANCE="${params.TARGET_SERVER}-db"
    }
    stages {
        stage('Verifying parameters') {
            steps {
                echo "Section: Verifying parameters"
                // sh """
                //     echo "Source DB: ${params.SOURCE_DB}"
                //     echo "Target DB: ${params.TARGET_DB}"
                //     if [ "`echo ${params.SOURCE_DB} | cut -c6`" = "e" ]; then
                //     echo Good This is E box ${params.SOURCE_DB}
                //     else
                //     echo Error: Please use rsxxxe
                //     exit
                //     fi 
                // """
            }
        }
        
        // stage("Trigger deployment") {
        //     steps {
        //         echo "Section: Trigger deployment"
        //         script {
        //             try {
        //                 build job: "swms-opco-deployment-without-healthcheck", parameters: [
        //                     string(name: 'target_server_name', value: "${params.TARGET_SERVER}.swms-np.us-east-1.aws.sysco.net"),
        //                     string(name: 'artifact_s3_bucket', value: "${params.artifact_s3_bucket}"),
        //                     string(name: 'platform', value: "${params.platform}"),
        //                     string(name: 'artifact_version', value: "${params.artifact_version}"),
        //                     string(name: 'artifact_name', value: "${params.artifact_name}"),
        //                     string(name: 'dba_masterfile_names', value: "${params.dba_masterfile_names}"),
        //                     string(name: 'master_file_retry_count', value: "${params.master_file_retry_count}")
        //                 ]
        //                 echo "Deployment Successful!"
        //             } catch (e) {
        //                 echo "Deployment Failed!"
        //                 throw e
        //             }
        //         }
        //     }
        // }
    }
    post {
        // always {
        //     script {
        //         logParser projectRulePath: "${WORKSPACE}/log_parse_rules" , useProjectRule: true
        //         sh """
        //             ssh -i $SSH_KEY ${SSH_KEY_USR}@rs1060b1.na.sysco.net "
        //             . ~/.profile;
        //             beoracle_ci rm -r /tempfs/11gtords/
        //             "
        //         """
        //     }
        // }
        success {
            script {
                echo 'Data migration from Oracle 11 AIX to Oracle 19 RDS is successful!'
            }
        }
        failure {
            script {
                echo 'Data migration from Oracle 11 AIX to Oracle 19 RDS is failed!'
            }
        }
    }
}
