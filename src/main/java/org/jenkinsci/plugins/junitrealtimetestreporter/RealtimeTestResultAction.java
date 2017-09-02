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
import hudson.Util;
import hudson.matrix.MatrixBuild;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.test.TestResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

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
public class RealtimeTestResultAction extends AbstractRealtimeTestResultAction {

    private static final Logger LOGGER = Logger.getLogger(RealtimeTestResultAction.class.getName());

    public RealtimeTestResultAction(final AbstractBuild<?, ?> owner) {

        super(owner);
    }

    @Override
    protected TestResult parse() {

        final JUnitResultArchiver archiver = getArchiver(this.owner);

        try {

            final long started = System.currentTimeMillis();
            final TestResult result = new JUnitParser(archiver.isKeepLongStdio())
                    .parse(getGlob(archiver), this.owner, null, null)
            ;
            LOGGER.log(Level.INFO, "Parsing took {0} ms", System.currentTimeMillis() - started);

            result.setParentAction(this);
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

    private String getGlob(final JUnitResultArchiver archiver) {

        String glob = archiver.getTestResults();
        // Ensure the GLOB work recursively
        if (this.owner instanceof MatrixBuild) {

            final String[] independentChunks = glob.split("[, ]+");
            for (int i = 0; i < independentChunks.length; i++) {

                independentChunks[i] = "**/" + independentChunks[i];
            }

            glob = Util.join(Arrays.asList(independentChunks), ", ");
        }

        return glob;
    }

    /*package*/ static JUnitResultArchiver getArchiver(AbstractBuild<?, ?> build) {

        if (build instanceof MavenModuleSetBuild || build instanceof MavenBuild) return new DummyArchiver();

        return getProject(build).getPublishersList().get(JUnitResultArchiver.class);
    }

    private static AbstractProject<?, ?> getProject(AbstractBuild<?, ?> build) {
        return build.getRootBuild().getParent();
    }

    /*package*/ static PerJobConfiguration getConfig(AbstractBuild<?, ?> build) {

        return PerJobConfiguration.getConfig(getProject(build));
    }

}
