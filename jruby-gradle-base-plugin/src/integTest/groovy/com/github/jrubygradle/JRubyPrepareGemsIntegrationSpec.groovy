package com.github.jrubygradle

import com.github.jrubygradle.testhelper.BasicProjectBuilder
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.*

import static org.gradle.api.logging.LogLevel.LIFECYCLE

/**
 * @author Schalk W. Cronjé.
 */
class JRubyPrepareGemsIntegrationSpec extends Specification {

    static final File CACHEDIR = new File( System.getProperty('TEST_CACHEDIR') ?: 'build/tmp/integrationTest/cache')
    static final File FLATREPO = new File( System.getProperty('TEST_FLATREPO') ?: 'build/tmp/integrationTest/flatRepo')
    static final boolean TESTS_ARE_OFFLINE = System.getProperty('TESTS_ARE_OFFLINE') != null
    static final File TESTROOT = new File( "${System.getProperty('TESTROOT') ?: 'build/tmp/integrationTest'}/jpgis")
    static final String TASK_NAME = 'RubyWax'
    static final String SLIM_VERSION = '2.0.2'
    static final String TEMPLE_VERSION = '0.6.10'
    static final String TILT_VERSION = '2.0.1'
    static final String OUR_GEM = "rubygems:slim:${SLIM_VERSION}"

    void setup() {
        if(TESTROOT.exists()) {
            TESTROOT.deleteDir()
        }
        TESTROOT.mkdirs()
    }

    def "Check that default 'jrubyPrepareGems' uses the correct directory"() {
        given:
            def project=BasicProjectBuilder.buildWithLocalRepo(TESTROOT,FLATREPO,CACHEDIR)
            def jrpg = project.tasks.jrubyPrepare
            project.jruby.gemInstallDir = TESTROOT.absolutePath

            project.dependencies {
                gems "${OUR_GEM}@gem"
            }
            project.evaluate()
            jrpg.copy()

        expect:
            new File(jrpg.outputDir,"gems/slim-${SLIM_VERSION}").exists()
    }

    def "Check if rack version gets resolved"() {
        given:
            def root= new File(TESTROOT, "rack-resolve")
            def project = BasicProjectBuilder.buildWithStdRepo(root,CACHEDIR)
            def jrpg = project.tasks.jrubyPrepare
            project.jruby.gemInstallDir = root.absolutePath

            project.dependencies {
                gems "rubygems:sinatra:1.4.5"
                gems "rubygems:rack:[0,)"
                gems "rubygems:lookout-rack-utils:3.1.0.12"
            }
            project.evaluate()
            jrpg.copy()

        expect:
            // since we need a version range in the setup the
            // resolved version here can vary over time
            new File(jrpg.outputDir,"gems/rack-1.5.5").exists()
    }

    def "Check if prerelease gem gets resolved"() {
        given:
            def root= new File(TESTROOT, "prerelease")
            def project = BasicProjectBuilder.buildWithStdRepo(root,CACHEDIR)
            def task = project.tasks.jrubyPrepare
            project.jruby.gemInstallDir = root.absolutePath

            project.dependencies {
                gems "rubygems:jar-dependencies:0.1.16.pre"
            }
            project.evaluate()
            task.copy()

        expect:
            new File(task.outputDir,"gems/jar-dependencies-0.1.16.pre").exists()
    }

//    @IgnoreIf({TESTS_ARE_OFFLINE})
    @Ignore
    def "Unpack our gem as normal"() {
        given:
            def project=BasicProjectBuilder.buildWithStdRepo(TESTROOT,CACHEDIR)
            def prepTask = project.task(TASK_NAME, type: JRubyPrepareGems)
            project.dependencies {
                gems OUR_GEM
            }
            project.configure(prepTask) {
                outputDir TESTROOT
                gems project.configurations.gems
            }
            project.evaluate()
            prepTask.copy()

        expect:
            new File(prepTask.outputDir,"gems/slim-${SLIM_VERSION}").exists()
            new File(prepTask.outputDir,"gems/temple-${TEMPLE_VERSION}").exists()
            new File(prepTask.outputDir,"gems/tilt-${TILT_VERSION}").exists()
    }

//    @IgnoreIf({TESTS_ARE_OFFLINE})
    @Ignore
    def "Unpack our gem, but without transitives"() {
        given:
            def project=BasicProjectBuilder.buildWithStdRepo(TESTROOT,CACHEDIR)
            def prepTask = project.task(TASK_NAME, type: JRubyPrepareGems)
            project.dependencies {
                gems (OUR_GEM) {
                    transitive = false
                }
            }
            project.configure(prepTask) {
                outputDir TESTROOT
                gems project.configurations.gems
            }
            project.evaluate()
            prepTask.copy()

        expect:
            new File(prepTask.outputDir,"gems/slim-${SLIM_VERSION}").exists()
            !new File(prepTask.outputDir,"gems/temple-${TEMPLE_VERSION}").exists()
            !new File(prepTask.outputDir,"gems/tilt-${TILT_VERSION}").exists()
   }

}
