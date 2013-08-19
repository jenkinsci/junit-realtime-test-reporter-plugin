/*
 * The MIT License
 *
 * Copyright (c) 2013 Red Hat, Inc.
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

import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.matrix.MatrixRun;
import hudson.matrix.MatrixBuild;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.Action;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TestResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.StaplerProxy;

/**
 * Action attached to the build at the time of running displaying test results in real time.
 *
 * This action is attached to building runs and detached as soon as the run
 * finishes. The action presents test result accumulated in workspace so far.
 * {@link RealtimeTestResultAction} and {@link TestResultAction} are never
 * attached at the same time so they share the same url space. As soon as the
 * underlying build finishes the realtime results will be replaced by final ones.
 *
 * @author ogondza
 */
public class RealtimeTestResultAction extends AbstractTestResultAction<RealtimeTestResultAction> implements StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(RealtimeTestResultAction.class.getName());

    private transient TestResult result;
    private transient long updated;

    public RealtimeTestResultAction(final AbstractBuild<?, ?> owner) {

        super(owner);
    }

    @Override
    public TestResult getResult() {

        // Refresh every 1/100 of a job estimated duration but not more often than every 5 seconds
        final long thrashold = Math.max(5000, owner.getEstimatedDuration() / 100);
        if (updated > System.currentTimeMillis() - thrashold) {
            LOGGER.fine("Cache hit");
            return result;
        }

        result = parse(this);
        updated = System.currentTimeMillis();
        return result;
    }

    @Override
    public int getFailCount() {

        if (getResult() == null) return 0;
        return getResult().getFailCount();
    }

    @Override
    public int getTotalCount() {

        if (getResult() == null) return 0;
        return getResult().getTotalCount();
    }

    public TestResult getTarget() {

        if (!owner.isBuilding()) {
            LOGGER.warning("Dangling RealtimeTestResultAction on " + owner + ". Probably not finalized correctly.");
            detachFrom(owner);
            throw new HttpRedirect(owner.getUrl());
        }

        if (getResult() != null) return getResult();

        return new NullTestResult(this);
    }

    @Override
    public String getDisplayName() {

        return "Realtime Test Result";
    }

    @Override
    public String getUrlName() {

        return "realtimeTestReport";
    }

    @Override
    public String getIconFileName() {

        return super.getIconFileName();
    }

    private static TestResult parse(final RealtimeTestResultAction action) {

        final JUnitResultArchiver archiver = getArchiver(action.owner);
        String glob = archiver.getTestResults();
        // Ensure the GLOB work recursively
        if (action.owner instanceof MatrixBuild) {

            final String[] independentChunks = glob.split("[, ]+");
            for (int i = 0; i < independentChunks.length; i++) {

                independentChunks[i] = "**/" + independentChunks[i];
            }

            glob = Util.join(Arrays.asList(independentChunks), ", ");
        }

        try {

            final long started = System.currentTimeMillis();
            final TestResult result = new JUnitParser(archiver.isKeepLongStdio())
                    .parse(glob, action.owner, null, null)
            ;
            LOGGER.log(Level.INFO, "Parsing took {0} ms", System.currentTimeMillis() - started);

            result.setParentAction(action);
            return result;
        } catch (AbortException ex) {
            // Thrown when there are no reports or no workspace witch is normal
            // at the beginning the build. This is also a signal that there are
            // no reports to update (already parsed was excluded and no new have
            // arrived so far).
            LOGGER.fine("No new reports found.");
        } catch (InterruptedException ex) {
            LOGGER.log(Level.WARNING, "Unable to parse", ex);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Unable to parse", ex);
        }

        return null;
    }

    private static JUnitResultArchiver getArchiver(AbstractBuild<?, ?> build) {

        if (build instanceof MavenModuleSetBuild || build instanceof MavenBuild) return new DummyArchiver();

        return getProject(build).getPublishersList().get(JUnitResultArchiver.class);
    }

    private static AbstractProject<?, ?> getProject(AbstractBuild<?, ?> build) {

        if (build instanceof MavenBuild) return getProject(((MavenBuild) build).getRootBuild());
        if (build instanceof MatrixRun) return getProject(((MatrixRun) build).getRootBuild());

        return build.getProject();
    }

    /*package*/ static PerJobConfiguration getConfig(AbstractBuild<?, ?> build) {

        return PerJobConfiguration.getConfig(getProject(build));
    }

    /*package*/ static void detachFrom(final AbstractBuild<?, ?> build) {

        final List<Action> actions = build.getActions();

        final Iterator<Action> iterator = actions.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {

            final Action action = iterator.next();
            if (action instanceof RealtimeTestResultAction) {

                LOGGER.info("Detaching RealtimeTestResultAction from " + build);
                actions.remove(action);
                ((RealtimeTestResultAction) action).result = null;
                removed = true;
            }
        }

        if (removed) try {
            build.save();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Extension
    public static class Attacher extends RunListener<Run<?, ?>> {

        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {

            final AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;

            if (!isApplicable(build)) return;

            build.addAction(new RealtimeTestResultAction(build));
        }

        @Override
        public void onFinalized(Run<?, ?> run) {

            final AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;

            detachFrom(build);
        }

        private static boolean isApplicable(final AbstractBuild<?, ?> build) {

            if (!getConfig(build).reportInRealtime) return false;

            if (build instanceof MavenModuleSetBuild) return true;

            if (getArchiver(build) == null) return false;

            return true;
        }
    }
}
