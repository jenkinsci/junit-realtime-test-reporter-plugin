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
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Job;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;

// TODO OptionalJobProperty to delete reportInRealtime field, and add a @Symbol for easy use from multibranch
public class PerJobConfiguration extends JobProperty<Job<?,?>> {

    private static final PerJobConfiguration REPORTING = new PerJobConfiguration(true);

    public final boolean reportInRealtime;

    private PerJobConfiguration(boolean reportInRealtime) {

        this.reportInRealtime = reportInRealtime;
    }

    public static boolean isActive(Job<?, ?> project) {
        PerJobConfiguration cfg = project.getProperty(PerJobConfiguration.class);
        return cfg != null && cfg.reportInRealtime;
    }

    @Extension
    public static class Descriptor extends JobPropertyDescriptor {

        @Override
        public boolean isApplicable(
                @SuppressWarnings("rawtypes") Class<? extends Job> jobType
        ) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Realtime test result report";
        }

        @Override
        public JobProperty<?> newInstance(
                StaplerRequest req, JSONObject formData
        ) throws FormException {

            if (formData.isNullObject()) return null;

            if (!formData.has("reportInRealtime")) return null;

            return REPORTING;
        }
    }
}
