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

import hudson.FilePath;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import java.util.Collections;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import com.google.common.base.Predicate;

public class RealtimeJUnitStepTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule rr = new RestartableJenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule().record(RealtimeJUnitStep.class.getPackage().getName(), Level.FINER);

    @Test
    public void smokes() {
        rr.then(r -> {
                WorkflowJob p = rr.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node {\n" +
                    "  realtimeJUnit('*.xml') {\n" +
                    "    semaphore 'pre'\n" +
                    "    writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'\n" +
                    "    semaphore 'mid'\n" +
                    "    writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error>b2 failed</error></testcase></testsuite>''', file: 'b.xml'\n" +
                    "    semaphore 'post'\n" +
                    "  }\n" +
                    "  deleteDir()\n" +
                    "}; semaphore 'final'", true));
                SemaphoreStep.success("pre/1", null);
                SemaphoreStep.success("mid/1", null);
                SemaphoreStep.success("post/1", null);
                SemaphoreStep.success("final/1", null);
                WorkflowRun b1 = rr.j.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
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
                rr.j.assertBuildStatus(null, b2);
        });
        rr.then(r -> {
                WorkflowRun b2 = rr.j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(2);
                AbstractRealtimeTestResultAction rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(0, rta.getTotalCount());
                assertEquals(0, rta.getFailCount());
                rr.j.assertBuildStatus(null, b2);
                SemaphoreStep.success("pre/2", null);
                SemaphoreStep.waitForStart("mid/2", b2);
                rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(2, rta.getTotalCount());
                assertEquals(0, rta.getFailCount());
                rr.j.assertBuildStatus(null, b2);
                SemaphoreStep.success("mid/2", null);
                SemaphoreStep.waitForStart("post/2", b2);
                rta = b2.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(4, rta.getTotalCount());
                assertEquals(1, rta.getFailCount());
                rr.j.assertBuildStatus(null, b2); // only final JUnitResultArchiver sets it to UNSTABLE
                SemaphoreStep.success("post/2", null);
                SemaphoreStep.waitForStart("final/2", b2);
                assertEquals(Collections.emptyList(), b2.getActions(AbstractRealtimeTestResultAction.class));
                SemaphoreStep.success("final/2", null);
                rr.j.assertBuildStatus(Result.UNSTABLE, rr.j.waitForCompletion(b2));
                TestResultAction a = b2.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
                assertEquals(Collections.emptyList(), b2.getActions(AbstractRealtimeTestResultAction.class));
        });
    }

    @Test
    public void brokenConnection() {
        rr.then(r -> {
                DumbSlave s = rr.j.createSlave("remote", null, null);
                WorkflowJob p = rr.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node('remote') {\n" +
                    "  realtimeJUnit('*.xml') {\n" +
                    "    writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'\n" +
                    "    writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error>b2 failed</error></testcase></testsuite>''', file: 'b.xml'\n" +
                    "    semaphore 'end'\n" +
                    "    archive 'a.xml'\n" + // should fail, so JUnit archiving will be a suppressed exception; otherwise would be primary
                    "  }\n" +
                    "}", true));
                WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("end/1", b1);
                assertNull(b1.getAction(TestResultAction.class));
                AbstractRealtimeTestResultAction rta = b1.getAction(AbstractRealtimeTestResultAction.class);
                assertNotNull(rta);
                assertEquals(4, rta.getTotalCount()); // as a side effect, ensures cache has been populated
                assertEquals(1, rta.getFailCount());
                s.toComputer().getChannel().close();
                SemaphoreStep.success("end/1", null);
                rr.j.assertBuildStatus(Result.FAILURE, rr.j.waitForCompletion(b1));
                assertEquals(Collections.emptyList(), b1.getActions(AbstractRealtimeTestResultAction.class));
                TestResultAction a = b1.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
        });
    }

    @Test
    public void ui() {
        rr.then(r -> {
                StepConfigTester t = new StepConfigTester(rr.j);
                RealtimeJUnitStep s = new RealtimeJUnitStep("*.xml");
                rr.j.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
                s.setKeepLongStdio(true);
                rr.j.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
                s.setSkipMarkingBuildUnstable(true);
                rr.j.assertEqualDataBoundBeans(s, t.configRoundTrip(s));
        });
    }

    @Test
    public void testResultDetails() {
        rr.then(r -> {
                WorkflowJob p = rr.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node {\n" +
                    "  stage('stage1') {\n" +
                    "    realtimeJUnit('a.xml') {\n" +
                    "      writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'\n" +
                    "    }\n" +
                    "  }\n" +
                    "  stage('stage2') {\n" +
                    "    realtimeJUnit('b.xml') {\n" +
                    "      writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error>b2 failed</error></testcase></testsuite>''', file: 'b.xml'\n" +
                    "    }\n" +
                    "  }\n" +
                    "}", true));
                WorkflowRun b1 = rr.j.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
                TestResultAction a = b1.getAction(TestResultAction.class);
                assertNotNull(a);
                assertEquals(4, a.getTotalCount());
                assertEquals(1, a.getFailCount());
                assertEquals(Collections.emptyList(), b1.getActions(AbstractRealtimeTestResultAction.class));

                FlowExecutionOwner owner = b1.asFlowExecutionOwner();
                FlowExecution execution = owner.getOrNull();
                DepthFirstScanner scanner = new DepthFirstScanner();

                FlowNode stage1Id = scanner.findFirstMatch(execution, new BlockNamePredicate("stage1"));
                TestResult stage1Results = a.getResult().getResultForPipelineBlock(stage1Id.getId());
                assertNotNull(stage1Results);
                assertEquals(2, stage1Results.getTotalCount());
                assertEquals(0, stage1Results.getFailCount());

                FlowNode stage2Id = scanner.findFirstMatch(execution, new BlockNamePredicate("stage2"));
                TestResult stage2Results = a.getResult().getResultForPipelineBlock(stage2Id.getId());
                assertNotNull(stage2Results);
                assertEquals(2, stage2Results.getTotalCount());
                assertEquals(1, stage2Results.getFailCount());
        });
    }

    @Test
    public void skipBuildUnstable() {
        rr.then(r -> {
            WorkflowJob j = r.jenkins.createProject(WorkflowJob.class, "currentBuildResultUnstable");
            j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                    "  node {\n" +
                    "    realtimeJUnit(testResults: 'b.xml', skipMarkingBuildUnstable: true) {\n" +
                    "      writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error>b2 failed</error></testcase></testsuite>''', file: 'b.xml'\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n", true));
            WorkflowRun run = r.waitForCompletion(j.scheduleBuild2(0).waitForStart());
            r.assertBuildStatus(Result.SUCCESS, run);
            TestResultAction a = run.getAction(TestResultAction.class);
            assertNotNull(a);
            DepthFirstScanner scanner = new DepthFirstScanner();
            FlowExecutionOwner owner = run.asFlowExecutionOwner();
            FlowExecution execution = owner.getOrNull();
            TestResult stageResults = a.getResult().getResultForPipelineBlock(scanner.findFirstMatch(execution, new BlockNamePredicate("first")).getId());
            assertNotNull(stageResults);
            assertEquals(2, stageResults.getTotalCount());
            assertEquals(1, stageResults.getFailCount());
        });
    }

    private static class BlockNamePredicate implements Predicate<FlowNode> {
        private final String blockName;
        public BlockNamePredicate(@Nonnull String blockName) {
            this.blockName = blockName;
        }
        @Override
        public boolean apply(@Nullable FlowNode input) {
            if (input != null) {
                LabelAction labelAction = input.getPersistentAction(LabelAction.class);
                if (labelAction != null && labelAction instanceof ThreadNameAction) {
                	return blockName.equals(((ThreadNameAction) labelAction).getThreadName());
                }
                return labelAction != null && blockName.equals(labelAction.getDisplayName());
            }
            return false;
        }
    }
    // TODO test distinct parallel / repeated archiving

}
