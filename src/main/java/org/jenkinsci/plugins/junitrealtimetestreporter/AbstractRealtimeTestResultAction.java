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

import hudson.AbortException;
import hudson.Main;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;
import hudson.tasks.test.AbstractTestResultAction;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.StaplerProxy;

abstract class AbstractRealtimeTestResultAction extends AbstractTestResultAction<AbstractRealtimeTestResultAction> implements StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(AbstractRealtimeTestResultAction.class.getName());

    protected TestResult result;
    private transient long updated;

    protected AbstractRealtimeTestResultAction() {}

    protected abstract TestResult parse() throws IOException, InterruptedException;

    @Override
    public TestResult getResult() {
        // Refresh every 1/100 of a job estimated duration but not more often than every 5 seconds
        final long threshold = Math.max(5000, run.getEstimatedDuration() / 100);
        // TODO possible improvements:
        // · always run parse in case result == null
        // · run parse regardless of cache if result.getTotalCount() == 0
        // · refresh no less often than every 1m even if job is estimated to take >100m
        if (updated > System.currentTimeMillis() - threshold && !Main.isUnitTest) {
            LOGGER.fine("Cache hit");
            return result;
        }
        try {
            final long started = System.currentTimeMillis(); // TODO use nanoTime
            // TODO this can block on Remoting and hang the UI; need to refresh results asynchronously
            result = parse();
            result.setParentAction(this);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Parsing of {0} test results took {1}ms", new Object[] {result.getTotalCount(), System.currentTimeMillis() - started});
            }
            updated = System.currentTimeMillis();
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
        return result;
    }

    @Override
    public int getFailCount() {
        if (getResult() == null) {
            return 0;
        }
        return getResult().getFailCount();
    }

    @Override
    public List<? extends hudson.tasks.test.TestResult> getFailedTests() {
        if (getResult() == null) {
            return Collections.emptyList();
        }

        return getResult().getFailedTests();
    }

    @Override
    public int getTotalCount() {
        if (getResult() == null) {
            return 0;
        }
        return getResult().getTotalCount();
    }

    @Override
    public TestResult getTarget() {
        if (!run.isBuilding()) {
            LOGGER.log(Level.WARNING, "Dangling RealtimeTestResultAction on {0}. Probably not finalized correctly.", run);
            detachAllFrom(run);
            throw new HttpRedirect(run.getUrl());
        }
        if (getResult() != null) {
            return getResult();
        }
        return new TestResult();
    }

    static void saveBuild(Run<?, ?> build) {
        try {
            build.save();
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
    }

    /*package*/ static void detachAllFrom(final Run<?, ?> build) {
        if (build.removeActions(AbstractRealtimeTestResultAction.class)) {
            LOGGER.log(Level.FINE, "Detaching RealtimeTestResultAction from {0}", build);
            saveBuild(build);
        }
    }

}
