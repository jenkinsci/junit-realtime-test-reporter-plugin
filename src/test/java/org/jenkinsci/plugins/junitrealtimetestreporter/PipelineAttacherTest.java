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

import hudson.model.Result;
import hudson.tasks.junit.TestResultAction;
import java.util.Collections;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class PipelineAttacherTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule rr = new RestartableJenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule().record(PipelineAttacher.class, Level.FINER).record(AbstractRealtimeTestResultAction.class, Level.FINE);

    @Test
    public void smokes() {
        rr.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = rr.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node {\n" +
                    "  semaphore 'pre'\n" +
                    "  writeFile text: '''<testsuite name='a'><testcase name='a1'/><testcase name='a2'/></testsuite>''', file: 'a.xml'\n" +
                    "  semaphore 'mid'\n" +
                    "  writeFile text: '''<testsuite name='b'><testcase name='b1'/><testcase name='b2'><error>b2 failed</error></testcase></testsuite>''', file: 'b.xml'\n" +
                    "  semaphore 'post'\n" +
                    "  junit '*.xml'\n" +
                    "  deleteDir()\n" +
                    "}; semaphore 'final'", true));
                p.addProperty(PerJobConfiguration.REPORTING);
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
            }
        });
        rr.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
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
            }
        });
    }

    // TODO test distinct globs, parallel, repeated archiving

}
