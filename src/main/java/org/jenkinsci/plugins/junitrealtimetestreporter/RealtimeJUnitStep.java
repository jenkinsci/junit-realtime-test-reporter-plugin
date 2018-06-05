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

import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.pipeline.JUnitResultsStepExecution;
import hudson.tasks.test.PipelineTestDetails;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.pickles.XStreamPickle;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class RealtimeJUnitStep extends Step {

    private static final Logger LOGGER = Logger.getLogger(RealtimeJUnitStep.class.getName());

    // Unfortunately keeping a field of type JUnitResultArchiver does not work well because realtimeJUnit(junit('*.xml')) would try to run the junit step immediately, so we need to inline its state:
    private final String testResults;
    private boolean keepLongStdio;
    private List<TestDataPublisher> testDataPublishers;
    private Double healthScaleFactor;
    private boolean allowEmptyResults;
    private Long parseInterval;

    @DataBoundConstructor
    public RealtimeJUnitStep(String testResults) {
        this.testResults = testResults;
    }

    public String getTestResults() {
        return testResults;
    }

    public boolean isKeepLongStdio() {
        return keepLongStdio;
    }

    @DataBoundSetter
    public void setKeepLongStdio(boolean keepLongStdio) {
        this.keepLongStdio = keepLongStdio;
    }

    @Nonnull
    public List<TestDataPublisher> getTestDataPublishers() {
        return testDataPublishers == null ? Collections.<TestDataPublisher>emptyList() : testDataPublishers;
    }

    @DataBoundSetter
    public void setTestDataPublishers(@Nonnull List<TestDataPublisher> testDataPublishers) {
        this.testDataPublishers = testDataPublishers;
    }

    public double getHealthScaleFactor() {
        return healthScaleFactor == null ? 1.0 : healthScaleFactor;
    }

    @DataBoundSetter
    public void setHealthScaleFactor(double healthScaleFactor) {
        this.healthScaleFactor = Math.max(0.0, healthScaleFactor);
    }

    public boolean isAllowEmptyResults() {
        return allowEmptyResults;
    }

    @DataBoundSetter
    public void setAllowEmptyResults(boolean allowEmptyResults) {
        this.allowEmptyResults = allowEmptyResults;
    }

    public Long getParseInterval() {
        return parseInterval;
    }

    @DataBoundSetter
    public void setParseInterval(Long parseInterval) {
        if (parseInterval == null || parseInterval.longValue() > 0) {
            this.parseInterval = parseInterval;
        }
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        JUnitResultArchiver delegate = new JUnitResultArchiver(testResults);
        delegate.setAllowEmptyResults(allowEmptyResults);
        delegate.setHealthScaleFactor(getHealthScaleFactor());
        delegate.setKeepLongStdio(keepLongStdio);
        delegate.setTestDataPublishers(getTestDataPublishers());
        return new Execution(context, delegate, parseInterval);
    }

    static class Execution extends StepExecution {

        private final JUnitResultArchiver archiver;
        private final Long parseInterval;

        Execution(StepContext context, JUnitResultArchiver archiver, Long parseInterval) {
            super(context);
            this.archiver = archiver;
            this.parseInterval = parseInterval;
        }

        @Override
        public boolean start() throws Exception {
            Run<?, ?> r = getContext().get(Run.class);
            String id = getContext().get(FlowNode.class).getId();
            r.addAction(new PipelineRealtimeTestResultAction(id, getContext(), getContext().get(FilePath.class), archiver.isKeepLongStdio(), archiver.getTestResults(), parseInterval));
            AbstractRealtimeTestResultAction.saveBuild(r);
            getContext().newBodyInvoker().withCallback(new Callback(id, archiver)).start();
            return false;
        }

        private static final long serialVersionUID = 1;

    }

    static class Callback extends BodyExecutionCallback {

        private final String id;
        private final JUnitResultArchiver archiver;

        Callback(String id, JUnitResultArchiver archiver) {
            this.id = id;
            this.archiver = archiver;
        }

        // TODO would be helpful to have a TailCall.finished overload taking success parameter
        @Override
        public void onSuccess(StepContext context, Object result) {
            try {
                finished(context, true);
            } catch (Exception x) {
                context.onFailure(x);
                return;
            }
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            try {
                finished(context, false);
            } catch (Exception x) {
                t.addSuppressed(x);
            }
            context.onFailure(t);
        }

        private void finished(StepContext context, boolean success) throws Exception {
            Run<?, ?> r = context.get(Run.class);
            TestResult provisional = null;
            for (PipelineRealtimeTestResultAction a : r.getActions(PipelineRealtimeTestResultAction.class)) {
                if (a.id.equals(id)) {
                    provisional = a.getResult();
                    r.removeAction(a);
                    LOGGER.log(Level.FINE, "clearing {0} from {1}", new Object[] {id, r});
                    AbstractRealtimeTestResultAction.saveBuild(r);
                    break;
                }
            }
            if (!success) {
                archiver.setAllowEmptyResults(true);
            }
            TaskListener listener = context.get(TaskListener.class);
            try {
                FilePath workspace = context.get(FilePath.class);
                workspace.mkdirs();
                Launcher launcher = context.get(Launcher.class);
                FlowNode node = context.get(FlowNode.class);

                List<FlowNode> enclosingBlocks = JUnitResultsStepExecution.getEnclosingStagesAndParallels(node);

                PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                pipelineTestDetails.setNodeId(id);
                pipelineTestDetails.setEnclosingBlocks(JUnitResultsStepExecution.getEnclosingBlockIds(enclosingBlocks));
                pipelineTestDetails.setEnclosingBlockNames(JUnitResultsStepExecution.getEnclosingBlockNames(enclosingBlocks));

                // TODO might block CPS VM thread. Not trivial to solve: JENKINS-43276
                TestResultAction action = JUnitResultArchiver.parseAndAttach(archiver, pipelineTestDetails, r, workspace, launcher, listener);

                if (action != null && action.getResult().getFailCount() > 0) {
                    r.setResult(Result.UNSTABLE);
                }
            } catch (Exception x) {
                if (provisional != null) {
                    listener.getLogger().println("Final archiving failed; recording " + provisional.getTotalCount() + " provisional test results.");
                    r.addAction(new TestResultAction(r, provisional, listener));
                }
                throw x;
            }
        }

        private static final long serialVersionUID = 1;

    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "realtimeJUnit";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Display JUnit test results as they appear";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(FilePath.class, Run.class, Launcher.class, TaskListener.class, FlowNode.class);
        }

        public Descriptor<?> getDelegateDescriptor() {
            return Jenkins.getInstance().getDescriptor(JUnitResultArchiver.class);
        }

    }

    // TODO perhaps better for workflow-support to register one for Describable<?> generally, in which case SCMVar.Pickler could be deleted as well
    @Extension
    public static class Pickler extends PickleFactory {

        @Override
        public Pickle writeReplace(Object object) {
            if (object instanceof JUnitResultArchiver || object instanceof TestDataPublisher) {
                return new XStreamPickle(object);
            } else {
                return null;
            }
        }

    }

}
