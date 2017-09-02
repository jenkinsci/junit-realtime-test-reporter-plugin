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

import hudson.Extension;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

/**
 * General purpose action attacher.
 *
 * @author ogondza
 */
@Extension
public class Attacher extends RunListener<Run<?, ?>> {

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        if ( !( run instanceof AbstractBuild )) {
            return;
        }
        final AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;

        if (!isApplicable(build)) return;

        build.addAction(new RealtimeTestResultAction());
        AbstractRealtimeTestResultAction.saveBuild(run);
    }

    @Override
    public void onFinalized(Run<?, ?> run) {
        AbstractRealtimeTestResultAction.detachAllFrom(run);
    }

    private static boolean isApplicable(final AbstractBuild<?, ?> build) {

        if (!RealtimeTestResultAction.getConfig(build).reportInRealtime) return false;

        if (build instanceof MavenModuleSetBuild) return true;

        if (RealtimeTestResultAction.getArchiver(build) == null) return false;

        return true;
    }
}
