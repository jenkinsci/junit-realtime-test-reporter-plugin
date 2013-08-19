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
import hudson.maven.MavenBuildProxy;
import hudson.maven.MavenBuildProxy.BuildCallable;
import hudson.maven.MavenReporter;
import hudson.maven.MavenReporterDescriptor;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModule;
import hudson.model.BuildListener;

import java.io.IOException;

import org.apache.maven.project.MavenProject;

/**
 * Attach and detach temporary action on Maven module build.
 *
 * @author ogondza
 */
public class MavenAttacher extends MavenReporter {

    @Override
    public boolean enterModule(
            MavenBuildProxy proxy, MavenProject pom, BuildListener listener
    ) throws InterruptedException, IOException {

        if (!proxy.isArchivingDisabled()) proxy.execute(new Attach());
        return true;
    }

    @Override
    public boolean leaveModule(
            MavenBuildProxy proxy, MavenProject pom, BuildListener listener
    ) throws InterruptedException, IOException {

        if (!proxy.isArchivingDisabled()) proxy.execute(new Detach());
        return true;
    }

    private static class Attach implements BuildCallable<Void, IOException> {

        public Void call(MavenBuild build) throws IOException, InterruptedException {

            if (RealtimeTestResultAction.getConfig(build).reportInRealtime) {

                build.addAction(new RealtimeTestResultAction(build));
            }
            return null;
        }
    }

    private static class Detach implements BuildCallable<Void, IOException> {

        public Void call(MavenBuild build) throws IOException, InterruptedException {

            if (RealtimeTestResultAction.getConfig(build).reportInRealtime) {

                RealtimeTestResultAction.detachFrom(build);
            }
            return null;
        }
    }

    @Extension
    public static class Descriptor extends MavenReporterDescriptor {

        @Override
        public String getDisplayName() {

            return null;
        }

        @Override
        public MavenReporter newAutoInstance(MavenModule module) {

            return new MavenAttacher();
        }
    }
}