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

import hudson.tasks.junit.TestResult;

public class TestProgress {

    private TestResult previousResult;
    private TestResult result;

    public TestProgress(TestResult previousResult, TestResult result) {
        this.previousResult = previousResult;
        this.result = result;
    }

    public String getEstimatedRemainingTime() {
        float remaining = Math.max(previousResult.getDuration() - result.getDuration(), 0);

        int minutes = (int) Math.floor((double) remaining / 60d);
        int seconds = (int) remaining % 60;

        if (minutes > 0) {
            return String.format("%d min %d sec", minutes, seconds);
        }

        return String.format("%d sec", seconds);
    }

    public int getCompletedTestsPercentage() {
        if ( getExpectedTests() == 0) {
            return 100;
        }

        return Math.min((int) Math.floor((double) getCompletedTests() / (double) getExpectedTests() * 100d), 100);
    }

    public int getTestsLeftPercentage() {
        return 100 - getCompletedTestsPercentage();
    }

    public int getCompletedTimePercentage() {
        if ( getExpectedTime() == 0) {
            return 100;
        }

        return Math.min((int) Math.floor((double) getCompletedTime() / (double) getExpectedTime() * 100d), 100);
    }

    public int getTimeLeftPercentage() {
        return 100 - getCompletedTimePercentage();
    }

    public int getCompletedTests() {
        return result.getTotalCount();
    }

    public int getExpectedTests() {
        return previousResult.getTotalCount();
    }

    public float getCompletedTime() {
        return result.getDuration();
    }

    public float getExpectedTime() {
        return previousResult.getDuration();
    }

    public String getStyle() {
        if (result.getFailCount() > 0) {
            return "red";
        }

        return "";
    }
}
