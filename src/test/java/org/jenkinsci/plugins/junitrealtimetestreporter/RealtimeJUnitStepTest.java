/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.junitrealtimetestreporter;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.junit.storage.JunitTestResultStorageConfiguration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import org.jenkinsci.plugins.database.GlobalDatabaseConfiguration;
import org.jenkinsci.plugins.database.h2.LocalH2Database;
import org.jenkinsci.plugins.junitrealtimetestreporter.storage.H2JunitTestResultStorage;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.LogRecorder;

import com.google.common.base.Predicate;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

@ParameterizedClass(name = "{index}: use pluggable storage [{0}]")
@ValueSource(booleans = { false,  true })
class RealtimeJUnitStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension extension = new JenkinsSessionExtension();

    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder().record(RealtimeJUnitStep.class.getPackage().getName(), Level.FINER);

    @SuppressWarnings("unused")
    @Parameter(0)
    private boolean usePluggableStorage;

    private void autoServer() {
        if (usePluggableStorage) {
            GlobalDatabaseConfiguration gdc = GlobalDatabaseConfiguration.get();
            gdc.setDatabase(null);
            LocalH2Database.setDefaultGlobalDatabase();
            LocalH2Database database = (LocalH2Database) gdc.getDatabase();
            gdc.setDatabase(new LocalH2Database(database.getPath(), true));
            JunitTestResultStorageConfiguration.get().setStorage(new H2JunitTestResultStorage());
        }
    }

    @Test
    void smokes() throws Throwable {
        extension.then(r -> {
                autoServer();
                WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        """
                                node {
                                  realtimeJUnit('*.xml') {
                                    semaphore 'pre'
                                    writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'
                                    semaphore 'mid'
                                    writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'b.xml'
                                    semaphore 'post'
                                  }
                                  deleteDir()
                                }; semaphore 'final'""", true));
                SemaphoreStep.success("pre/1", null);
                SemaphoreStep.success("mid/1", null);
                SemaphoreStep.success("post/1", null);
                SemaphoreStep.success("final/1", null);
                WorkflowRun b1 = r.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
                TestResultAction a = b1.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
                assertEquals(Collections.emptyList(), b1.getActions(AbstractRealtimeTestResultAction.class));
                WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("pre/2", b2);
                AbstractRealtimeTestResultAction rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(0, rta.getTotalCount());
                assertEquals(0, rta.getFailCount());
                r.assertBuildStatus(null, b2);
        });
        extension.then(r -> {
                autoServer();
                WorkflowRun b2 = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(2);
                AbstractRealtimeTestResultAction rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(0, rta.getTotalCount());
                assertEquals(0, rta.getFailCount());
                r.assertBuildStatus(null, b2);
                SemaphoreStep.success("pre/2", null);
                SemaphoreStep.waitForStart("mid/2", b2);
                rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(2, rta.getTotalCount());
                assertEquals(0, rta.getFailCount());
                r.assertBuildStatus(null, b2);
                SemaphoreStep.success("mid/2", null);
                SemaphoreStep.waitForStart("post/2", b2);
                rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(4, rta.getTotalCount());
                assertEquals(1, rta.getFailCount());
                r.assertBuildStatus(null, b2); // only final JUnitResultArchiver sets it to UNSTABLE
                SemaphoreStep.success("post/2", null);
                SemaphoreStep.waitForStart("final/2", b2);
                assertEquals(Collections.emptyList(), b2.getActions(AbstractRealtimeTestResultAction.class));
                SemaphoreStep.success("final/2", null);
                r.assertBuildStatus(Result.UNSTABLE, r.waitForCompletion(b2));
                TestResultAction a = b2.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
                assertEquals(Collections.emptyList(), b2.getActions(AbstractRealtimeTestResultAction.class));
        });
    }

    @Test
    void brokenConnection() throws Throwable {
        extension.then(r -> {
                autoServer();
                DumbSlave s = r.createSlave("remote", null, null);
                WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        """
                                node('remote') {
                                  realtimeJUnit('*.xml') {
                                    writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'
                                    writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'b.xml'
                                    semaphore 'end'
                                    archive 'a.xml' // should fail, so JUnit archiving will be a suppressed exception; otherwise would be primary
                                  }
                                }""", true));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("end/1", b1);
                assertNull(b1.getAction(TestResultAction.class));
                AbstractRealtimeTestResultAction rta = b1.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(4, rta.getTotalCount()); // as a side effect, ensures cache has been populated
                assertEquals(1, rta.getFailCount());
                s.toComputer().getChannel().close();
                SemaphoreStep.success("end/1", null);
                r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b1));
                assertEquals(Collections.emptyList(), b1.getActions(AbstractRealtimeTestResultAction.class));
                TestResultAction a = b1.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
        });
    }

    @Test
    void ui() throws Throwable {
        extension.then(r -> {
                autoServer();
                StepConfigTester t = new StepConfigTester(r);
                RealtimeJUnitStep s = new RealtimeJUnitStep("*.xml");
                r.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
                s.setKeepLongStdio(true);
                r.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
                s.setStdioRetention("FAILED");
                r.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
                s.setSkipMarkingBuildUnstable(true);
                r.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
        });
    }

    @Test
    void testResultDetails() throws Throwable {
        extension.then(r -> {
                autoServer();
                WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                        """
                                node {
                                  stage('stage1') {
                                    realtimeJUnit('a.xml') {
                                      writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'
                                    }
                                  }
                                  stage('stage2') {
                                    realtimeJUnit('b.xml') {
                                      writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'b.xml'
                                    }
                                  }
                                }""", true));
                WorkflowRun b1 = r.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
                TestResultAction a = b1.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
                assertEquals(Collections.emptyList(), b1.getActions(AbstractRealtimeTestResultAction.class));

                FlowExecutionOwner owner = b1.asFlowExecutionOwner();
                FlowExecution execution = owner.getOrNull();
                DepthFirstScanner scanner = new DepthFirstScanner();
                ForkScanner forkScanner = new ForkScanner();

                FlowNode stage1Id = scanner.findFirstMatch(execution, new BlockNamePredicate("stage1"));
                FlowNode stage2Id = scanner.findFirstMatch(execution, new BlockNamePredicate("stage2"));
                
                if (!usePluggableStorage) {
                    TestResult stage1Results = a.getResult().getResultForPipelineBlock(stage1Id.getId());
                    assertNotNull(stage1Results);
                    assertEquals(2, stage1Results.getTotalCount());
                    assertEquals(0, stage1Results.getFailCount());

                    TestResult stage2Results = a.getResult().getResultForPipelineBlock(stage2Id.getId());
                    assertNotNull(stage2Results);
                    assertEquals(2, stage2Results.getTotalCount());
                    assertEquals(1, stage2Results.getFailCount());
                }
                
                List<FlowNode> realtimeJUnitSteps = forkScanner.filteredNodes(execution, new FunctionNamePredicate("realtimeJUnit"));
                assertEquals(2, realtimeJUnitSteps.size());
                
                FlowNode realtimeJUnitStep1 = realtimeJUnitSteps.stream().filter(f -> f.getAllEnclosingIds().contains(stage1Id.getId())).findFirst().get();
                TestResult stage1Results = a.getResult().getResultByNodes(Arrays.asList(realtimeJUnitStep1.getId()));
                assertNotNull(stage1Results);
                assertEquals(2, stage1Results.getTotalCount());
                assertEquals(0, stage1Results.getFailCount());
                
                FlowNode realtimeJUnitStep2 = realtimeJUnitSteps.stream().filter(f -> f.getAllEnclosingIds().contains(stage2Id.getId())).findFirst().get();
                TestResult stage2Results = a.getResult().getResultByNodes(Arrays.asList(realtimeJUnitStep2.getId()));
                assertNotNull(stage2Results);
                assertEquals(2, stage2Results.getTotalCount());
                assertEquals(1, stage2Results.getFailCount());
        });
    }

    @Test
    void skipBuildUnstable() throws Throwable {
        extension.then(r -> {
            autoServer();
            WorkflowJob j = r.jenkins.createProject(WorkflowJob.class, "currentBuildResultUnstable");
            j.setDefinition(new CpsFlowDefinition("""
                    stage('first') {
                      node {
                        realtimeJUnit(testResults: 'b.xml', skipMarkingBuildUnstable: true) {
                          writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'b.xml'
                        }
                      }
                    }
                    """, true));
            WorkflowRun run = r.waitForCompletion(j.scheduleBuild2(0).waitForStart());
            r.assertBuildStatus(Result.SUCCESS, run);
            TestResultAction a = run.getAction(TestResultAction.class);
            assertNotNull(a);
            DepthFirstScanner scanner = new DepthFirstScanner();
            FlowExecutionOwner owner = run.asFlowExecutionOwner();
            FlowExecution execution = owner.getOrNull();

            FlowNode stage = scanner.findFirstMatch(execution, new BlockNamePredicate("first"));
            
            if (!usePluggableStorage) {
                TestResult stageResults = a.getResult().getResultForPipelineBlock(stage.getId());
                assertNotNull(stageResults);
                assertEquals(2, stageResults.getTotalCount());
                assertEquals(1, stageResults.getFailCount());
            }
            
            FlowNode realtimeJUnitStep = scanner.findFirstMatch(execution, new FunctionNamePredicate("realtimeJUnit"));
            
            TestResult stageResults = a.getResult().getResultByNodes(Arrays.asList(realtimeJUnitStep.getId()));
            assertNotNull(stageResults);
            assertEquals(2, stageResults.getTotalCount());
            assertEquals(1, stageResults.getFailCount());
        });
    }

    @Test
    void testProgressNoStageSingleInstance() throws Throwable {
        extension.then(r -> {
            autoServer();
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            node {
                              realtimeJUnit('*.xml') {
                                semaphore 'pre'
                                writeFile text: '''<testsuite name='a' time='4'><testcase name='a1' time='1'/><testcase name='a2' time='3'/></testsuite>''', file: 'a.xml'
                                semaphore 'mid'
                                writeFile text: '''<testsuite name='b' time='6'><testcase name='b1' time='2'/><testcase name='b2' time='4'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'b.xml'
                                semaphore 'post'
                              }
                              deleteDir()
                            }; semaphore 'final'""", true));
            SemaphoreStep.success("pre/1", null);
            SemaphoreStep.success("mid/1", null);
            SemaphoreStep.success("post/1", null);
            SemaphoreStep.success("final/1", null);
            p.scheduleBuild2(0).get();
    
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    
            SemaphoreStep.waitForStart("pre/2", b2);
            AbstractRealtimeTestResultAction rta = b2.getAction(AbstractRealtimeTestResultAction.class);
            assertNotNull(rta);
            TestResult result = rta.getResult();
            assertNull(result);
            TestProgress progress = rta.getTestProgress();
            assertNull(progress);
            SemaphoreStep.success("pre/2", null);
    
            SemaphoreStep.waitForStart("mid/2", b2);
            result = rta.getResult();
            assertNotNull(result);
            progress = rta.getTestProgress();
            assertNotNull(progress);
            assertEquals(4, progress.getExpectedTests());
            assertEquals(2, progress.getCompletedTests());
            assertEquals(50, progress.getCompletedTestsPercentage());
            assertEquals(50, progress.getTestsLeftPercentage());
            assertEquals(10, progress.getExpectedTime(), 0);
            assertEquals(4, progress.getCompletedTime(), 0);
            assertEquals(40, progress.getCompletedTimePercentage());
            assertEquals(60, progress.getTimeLeftPercentage());
            assertEquals("6 sec", progress.getEstimatedRemainingTime());
            SemaphoreStep.success("mid/2", null);
    
            SemaphoreStep.waitForStart("post/2", b2);
            result = rta.getResult();
            assertNotNull(result);
            progress = rta.getTestProgress();
            assertNotNull(progress);
            assertEquals(4, progress.getExpectedTests());
            assertEquals(4, progress.getCompletedTests());
            assertEquals(100, progress.getCompletedTestsPercentage());
            assertEquals(0, progress.getTestsLeftPercentage());
            assertEquals(10, progress.getExpectedTime(), 0);
            assertEquals(10, progress.getCompletedTime(), 0);
            assertEquals(100, progress.getCompletedTimePercentage());
            assertEquals(0, progress.getTimeLeftPercentage());
            assertEquals("0 sec", progress.getEstimatedRemainingTime());
            SemaphoreStep.success("post/2", null);
    
            SemaphoreStep.success("final/2", null);
        });
    }

    @Test
    void testProgressParallelStagesAndRestart() throws Throwable {
        extension.then(r -> {
            autoServer();
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            node {
                              parallel('firstBranch': {
                                stage('stage1') {
                                  realtimeJUnit('stage1_*.xml') {
                                    semaphore 'pre1'
                                    writeFile text: '''<testsuite name='a' time='4'><testcase name='a1' time='1'/><testcase name='a2' time='3'/></testsuite>''', file: 'stage1_a.xml'
                                    semaphore 'mid1'
                                    writeFile text: '''<testsuite name='b' time='6'><testcase name='b1' time='2'/><testcase name='b2' time='4'><error message='b2 failed'>b2 failed</error></testcase></testsuite>''', file: 'stage1_b.xml'
                                    semaphore 'post1'
                                  }
                                }
                              }, 'secondBranch': {
                                stage('stage2') {
                                  realtimeJUnit('stage2_*.xml') {
                                    semaphore 'pre2'
                                    writeFile text: '''<testsuite name='c' time='12'><testcase name='c1' time='4'/><testcase name='c2' time='5'/><testcase name='c3' time='3'/></testsuite>''', file: 'stage2_c.xml'
                                    semaphore 'mid2'
                                    writeFile text: '''<testsuite name='d' time='16'><testcase name='d1' time='7'/><testcase name='d2' time='9'><error message='d2 failed'>d2 failed</error></testcase></testsuite>''', file: 'stage2_d.xml'
                                    semaphore 'post2'
                                  }
                                }
                              })
                              deleteDir()
                            }; semaphore 'final'""", true));
            SemaphoreStep.success("pre1/1", null);
            SemaphoreStep.success("pre2/1", null);
            SemaphoreStep.success("mid1/1", null);
            SemaphoreStep.success("mid2/1", null);
            SemaphoreStep.success("post1/1", null);
            SemaphoreStep.success("post2/1", null);
            SemaphoreStep.success("final/1", null);
            p.scheduleBuild2(0).get();
    
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
    
            SemaphoreStep.waitForStart("pre1/2", b2);
            SemaphoreStep.waitForStart("pre2/2", b2);
        });
        extension.then(r -> {
            autoServer();
            WorkflowRun b2 = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(2);
            List<AbstractRealtimeTestResultAction> actions = b2.getActions(AbstractRealtimeTestResultAction.class);
            assertEquals(2, actions.size());
            
            AbstractRealtimeTestResultAction rta1 = actions.get(0);
            AbstractRealtimeTestResultAction rta2 = actions.get(1);
            
            TestResult result1 = rta1.getResult();
            assertNull(result1);
            TestProgress progress1 = rta1.getTestProgress();
            assertNull(progress1);
            
            TestResult result2 = rta2.getResult();
            assertNull(result2);
            TestProgress progress2 = rta2.getTestProgress();
            assertNull(progress2);

            SemaphoreStep.success("pre1/2", null);
            SemaphoreStep.success("pre2/2", null);
            SemaphoreStep.waitForStart("mid1/2", b2);
            SemaphoreStep.waitForStart("mid2/2", b2);
            if (rta1.getTotalCount() == 3) {
                // switch we got the actions in reverse order
                rta1 = actions.get(1);
                rta2 = actions.get(0);
            }
            
            result1 = rta1.getResult();
            assertNotNull(result1);
            progress1 = rta1.getTestProgress();
            assertNotNull(progress1);
            assertEquals(4, progress1.getExpectedTests());
            assertEquals(2, progress1.getCompletedTests());
            assertEquals(50, progress1.getCompletedTestsPercentage());
            assertEquals(50, progress1.getTestsLeftPercentage());
            assertEquals(10, progress1.getExpectedTime(), 0);
            assertEquals(4, progress1.getCompletedTime(), 0);
            assertEquals(40, progress1.getCompletedTimePercentage());
            assertEquals(60, progress1.getTimeLeftPercentage());
            assertEquals("6 sec", progress1.getEstimatedRemainingTime());
            SemaphoreStep.success("mid1/2", null);
    
            result2 = rta2.getResult();
            assertNotNull(result2);
            progress2 = rta2.getTestProgress();
            assertNotNull(progress2);
            assertEquals(5, progress2.getExpectedTests());
            assertEquals(3, progress2.getCompletedTests());
            assertEquals(60, progress2.getCompletedTestsPercentage());
            assertEquals(40, progress2.getTestsLeftPercentage());
            assertEquals(28, progress2.getExpectedTime(), 0);
            assertEquals(12, progress2.getCompletedTime(), 0);
            assertEquals(42, progress2.getCompletedTimePercentage());
            assertEquals(58, progress2.getTimeLeftPercentage());
            assertEquals("16 sec", progress2.getEstimatedRemainingTime());
            SemaphoreStep.success("mid2/2", null);
    
            SemaphoreStep.waitForStart("post1/2", b2);
            result1 = rta1.getResult();
            assertNotNull(result1);
            progress1 = rta1.getTestProgress();
            assertNotNull(progress1);
            assertEquals(4, progress1.getExpectedTests());
            assertEquals(4, progress1.getCompletedTests());
            assertEquals(100, progress1.getCompletedTestsPercentage());
            assertEquals(0, progress1.getTestsLeftPercentage());
            assertEquals(10, progress1.getExpectedTime(), 0);
            assertEquals(10, progress1.getCompletedTime(), 0);
            assertEquals(100, progress1.getCompletedTimePercentage());
            assertEquals(0, progress1.getTimeLeftPercentage());
            assertEquals("0 sec", progress1.getEstimatedRemainingTime());
            SemaphoreStep.success("post1/2", null);
    
            SemaphoreStep.waitForStart("post2/2", b2);
            result2 = rta2.getResult();
            assertNotNull(result2);
            progress2 = rta2.getTestProgress();
            assertNotNull(progress2);
            assertEquals(5, progress2.getExpectedTests());
            assertEquals(5, progress2.getCompletedTests());
            assertEquals(100, progress2.getCompletedTestsPercentage());
            assertEquals(0, progress2.getTestsLeftPercentage());
            assertEquals(28, progress2.getExpectedTime(), 0);
            assertEquals(28, progress2.getCompletedTime(), 0);
            assertEquals(100, progress2.getCompletedTimePercentage());
            assertEquals(0, progress2.getTimeLeftPercentage());
            assertEquals("0 sec", progress2.getEstimatedRemainingTime());
            SemaphoreStep.success("post2/2", null);
    
            SemaphoreStep.success("final/2", null);
        });
    }

    private static class BlockNamePredicate implements Predicate<FlowNode> {

        private final String blockName;

        public BlockNamePredicate(@NonNull String blockName) {
            this.blockName = blockName;
        }

        @Override
        public boolean apply(@Nullable FlowNode input) {
            if (input != null) {
                LabelAction labelAction = input.getPersistentAction(LabelAction.class);
                if (labelAction instanceof ThreadNameAction) {
                	return blockName.equals(((ThreadNameAction) labelAction).getThreadName());
                }
                return labelAction != null && blockName.equals(labelAction.getDisplayName());
            }
            return false;
        }
    }

    private static class FunctionNamePredicate implements Predicate<FlowNode> {

        private final String functionName;

        public FunctionNamePredicate(@NonNull String functionName) {
            this.functionName = functionName;
        }

        @Override
        public boolean apply(@Nullable FlowNode input) {
            if (input != null) {
                return functionName.equals(input.getDisplayFunctionName());
            }
            return false;
        }
    }
    // TODO test distinct parallel / repeated archiving
}
