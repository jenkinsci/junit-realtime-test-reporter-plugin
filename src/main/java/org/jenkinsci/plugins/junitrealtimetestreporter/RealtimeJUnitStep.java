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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.StdioRetention;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResultSummary;
import hudson.tasks.junit.pipeline.JUnitResultsStepExecution;
import hudson.tasks.test.PipelineTestDetails;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.junit.storage.FileJunitTestResultStorage;
import io.jenkins.plugins.junit.storage.JunitTestResultStorage;
import io.jenkins.plugins.junit.storage.JunitTestResultStorage.RemotePublisher;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.pickles.XStreamPickle;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import static java.util.Objects.requireNonNull;

public class RealtimeJUnitStep extends Step {

    private static final Logger LOGGER = Logger.getLogger(RealtimeJUnitStep.class.getName());

    // Unfortunately keeping a field of type JUnitResultArchiver does not work well because realtimeJUnit(junit('*.xml')) would try to run the junit step immediately, so we need to inline its state:
    private final String testResults;
    private String stdioRetention;
    private List<TestDataPublisher> testDataPublishers;
    private Double healthScaleFactor;
    private boolean allowEmptyResults;
    private boolean skipMarkingBuildUnstable;
    private Long parseInterval;

    @DataBoundConstructor
    public RealtimeJUnitStep(String testResults) {
        this.testResults = testResults;
    }
    
    public String getTestResults() {
        return testResults;
    }

    @Deprecated
    public boolean isKeepLongStdio() {
        return StdioRetention.parse(stdioRetention) == StdioRetention.ALL;
    }

    @DataBoundSetter
    @Deprecated
    public void setKeepLongStdio(boolean keepLongStdio) {
        this.stdioRetention = StdioRetention.fromKeepLongStdio(keepLongStdio).name();
    }

    public String getStdioRetention() {
        return stdioRetention == null ? StdioRetention.DEFAULT.name() : stdioRetention;
    }

    @DataBoundSetter
    public void setStdioRetention(String stdioRetention) {
        this.stdioRetention = stdioRetention;
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

    public boolean isSkipMarkingBuildUnstable() {
        return skipMarkingBuildUnstable;
    }

    @DataBoundSetter
    public void setSkipMarkingBuildUnstable(boolean skipMarkingBuildUnstable) {
        this.skipMarkingBuildUnstable = skipMarkingBuildUnstable;
    }

    public Long getParseInterval() {
        return parseInterval;
    }

    @DataBoundSetter
    public void setParseInterval(Long parseInterval) {
        if (parseInterval == null || parseInterval == 0L) {
            this.parseInterval = null;
        } else {
            this.parseInterval = parseInterval;
        }
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        JUnitResultArchiver delegate = new JUnitResultArchiver(testResults);
        delegate.setAllowEmptyResults(allowEmptyResults);
        delegate.setHealthScaleFactor(getHealthScaleFactor());
        delegate.setStdioRetention(stdioRetention);
        delegate.setTestDataPublishers(getTestDataPublishers());
        delegate.setSkipMarkingBuildUnstable(isSkipMarkingBuildUnstable());
        // step takes value in milliseconds but users provide in seconds
        Long parseInterval = this.parseInterval != null ? this.parseInterval * 1000 : null;
        return new Execution2(context, delegate, parseInterval);
    }

    @SuppressFBWarnings("SE_BAD_FIELD") // FIXME
    static class Execution2 extends GeneralNonBlockingStepExecution {
        private final JUnitResultArchiver archiver;
        private final Long parseInterval;

        Execution2(StepContext context, JUnitResultArchiver archiver, Long parseInterval) {
            super(context);
            this.archiver = archiver;
            this.parseInterval = parseInterval;
        }

        @Override
        public boolean start() {
            run(this::doStart);
            return false;
        }

        private void doStart() throws IOException, InterruptedException {
            StepContext context = getContext();
            Run<?, ?> r = context.get(Run.class);
            FlowNode flowNode = context.get(FlowNode.class);
            String id = requireNonNull(flowNode).getId();
            requireNonNull(r).addAction(new PipelineRealtimeTestResultAction(
                            id,
                            context.get(FilePath.class),
                            archiver.isKeepLongStdio(),
                            archiver.getTestResults(),
                            context,
                            parseInterval
                    )
            );
            AbstractRealtimeTestResultAction.saveBuild(r);
            context.newBodyInvoker().withCallback(new Callback2(id, archiver)).start();
        }

        @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Handled by 'Pickler' below")
        private final class Callback2 extends BodyExecutionCallback {

            private final String id;
            private final JUnitResultArchiver archiver;

            Callback2(String id, JUnitResultArchiver archiver) {
                this.id = id;
                this.archiver = archiver;
            }

            // TODO would be helpful to have a TailCall.finished overload taking success parameter
            @Override
            public void onSuccess(StepContext context, Object result) {
                run(() -> {
                    try {
                        finished(id, archiver, context, true);
                    } catch (Exception x) {
                        context.onFailure(x);
                        return;
                    }
                    context.onSuccess(result);
                });
            }

            @Override
            public void onFailure(StepContext context, Throwable t) {
                run(() -> {
                    try {
                        finished(id, archiver, context, false);
                    } catch (Exception x) {
                        t.addSuppressed(x);
                    }
                    context.onFailure(t);
                });
            }

            private static final long serialVersionUID = 1;

        }

    }

    // Retained for binary compat across upgrades, can be removed at some point later
    @SuppressFBWarnings("SE_BAD_FIELD") // FIXME
    @Deprecated
    static class Execution extends StepExecution {

        private final JUnitResultArchiver archiver;

        Execution(StepContext context, JUnitResultArchiver archiver) {
            super(context);
            this.archiver = archiver;
        }

        @Override
        public boolean start() throws IOException, InterruptedException {
            doStart();
            return false;
        }

        // not much point extracting the method, can't use the same Callback unfortunately due to needing to be an enclosing class
        // to access #run()
        @SuppressWarnings("DuplicatedCode")
        void doStart() throws IOException, InterruptedException {
            StepContext context = getContext();
            Run<?, ?> r = context.get(Run.class);
            FlowNode flowNode = context.get(FlowNode.class);
            String id = requireNonNull(flowNode).getId();
            requireNonNull(r).addAction(new PipelineRealtimeTestResultAction(
                            id,
                            context.get(FilePath.class),
                            archiver.isKeepLongStdio(),
                            archiver.getTestResults(),
                            context,
                            null
                    )
            );
            AbstractRealtimeTestResultAction.saveBuild(r);
            context.newBodyInvoker().withCallback(new Callback(id, archiver)).start();
        }

        private static final long serialVersionUID = 1;

    }

    private static void finished(String id, JUnitResultArchiver archiver, StepContext context, boolean success) throws Exception {
        Run<?, ?> r = context.get(Run.class);
        TestResult provisional = null;
        for (PipelineRealtimeTestResultAction a : r.getActions(PipelineRealtimeTestResultAction.class)) {
            if (a.id.equals(id)) {
                provisional = a.getResult();
                r.removeAction(a);
                LOGGER.log(Level.FINE, "clearing {0} from {1}", new Object[]{id, r});
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

            TestResultSummary summary = JUnitResultArchiver.parseAndSummarize(archiver, pipelineTestDetails, r, workspace, launcher, listener);

            if (summary.getFailCount() > 0) {
                int testFailures = summary.getFailCount();
                if (testFailures > 0) {
                    node.addOrReplaceAction(new WarningAction(Result.UNSTABLE).withMessage(testFailures + " tests failed"));
                    if (!archiver.isSkipMarkingBuildUnstable()) {
                        r.setResult(Result.UNSTABLE);
                    }
                }
            }
        } catch (Exception x) {
            if (provisional != null) {
                listener.getLogger().println("Final archiving failed; recording " + provisional.getTotalCount() + " provisional test results.");

                JunitTestResultStorage storage = JunitTestResultStorage.find();
                if (storage instanceof FileJunitTestResultStorage) {
                    r.addAction(new TestResultAction(r, provisional, listener));
                } else {
                    RemotePublisher publisher = storage.createRemotePublisher(r);
                    publisher.publish(provisional, listener);
                    r.addAction(new TestResultAction(r, new TestResult(storage.load(r.getParent().getFullName(), r.getNumber())), listener));
                }
            }
            throw x;
        }
    }

    // Retained for binary compatibility during upgrade can be removed after some time
    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "Handled by 'Pickler' below")
    @Deprecated
    private static final class Callback extends BodyExecutionCallback {

        private final String id;
        private final JUnitResultArchiver archiver;

        Callback(String id, JUnitResultArchiver archiver) {
            this.id = id;
            this.archiver = archiver;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            try {
                finished(id, archiver, context, true);
            } catch (Exception x) {
                context.onFailure(x);
                return;
            }
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            try {
                finished(id, archiver, context, false);
            } catch (Exception x) {
                context.onFailure(x);
                return;
            }
            context.onFailure(t);
        }
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

        // Hack: using the delegate pattern with a <st:include> Jelly element doesn't
        // seem to pick up doFillXxxItems methods automatically, so we have to explicitly
        // delegate to the JUnitResultArchiver's descriptor class
        public ListBoxModel doFillStdioRetentionItems(@QueryParameter("stdioRetention") String value) {
            return new JUnitResultArchiver.DescriptorImpl().doFillStdioRetentionItems(value);
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
