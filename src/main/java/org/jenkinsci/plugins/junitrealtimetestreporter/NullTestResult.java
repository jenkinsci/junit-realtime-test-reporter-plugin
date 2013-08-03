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

import hudson.model.AbstractBuild;
import hudson.tasks.test.TestObject;
import hudson.tasks.test.TestResult;

/**
 * Null {@link TestResult} implementation to hold no results
 *
 * @author ogondza
 */
/*package*/ final class NullTestResult extends TestResult {

    private final RealtimeTestResultAction action;

    /*package*/ NullTestResult(final RealtimeTestResultAction action) {

        this.action = action;
    }

    @Override
    public String getTitle() {

        return action.getDisplayName();
    }

    public String getDisplayName() {

        return getTitle();
    }

    @Override
    public AbstractBuild<?, ?> getOwner() {

        return action.owner;
    }

    @Override
    public TestObject getParent() {

        return null; // I am a parent
    }

    @Override
    public TestResult findCorrespondingResult(String id) {

        throw new UnsupportedOperationException("No results here");
    }
}
