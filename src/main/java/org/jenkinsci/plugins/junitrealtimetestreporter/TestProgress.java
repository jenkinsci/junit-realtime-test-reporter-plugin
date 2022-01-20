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
    
    private int expectedTests;
    private float expectedTime;

    private int completedTests;
    private float completedTime;
    
    private int completedTestsPercentage = -1;
    private int completedTimePercentage = -1;

    private String estimatedRemainingTime;

    public TestProgress(int expectedTests, float expectedTime, TestResult result) {
        this.expectedTests = expectedTests;
        this.expectedTime = expectedTime;
        
        completedTests = result.getTotalCount();
        completedTime = result.getDuration();
    }

    public String getEstimatedRemainingTime() {
        if (estimatedRemainingTime == null) {
            float remaining = Math.max(expectedTime - completedTime, 0);
    
            int minutes = (int) Math.floor((double) remaining / 60d);
            int seconds = (int) remaining % 60;
    
            estimatedRemainingTime = minutes > 0 ? 
                String.format("%d min %d sec", minutes, seconds) :
                String.format("%d sec", seconds);
        }

        return estimatedRemainingTime;
    }

    public int getCompletedTestsPercentage() {
        if (completedTestsPercentage < 0) {
            if (expectedTests == 0) {
                completedTestsPercentage = 100;
            } else {
                completedTestsPercentage = Math.min((int) Math.floor((double) completedTests / (double) expectedTests * 100d), 100);
            }
        }
        
        return completedTestsPercentage;
    }

    public int getTestsLeftPercentage() {
        return 100 - getCompletedTestsPercentage();
    }

    public int getCompletedTimePercentage() {
        if (completedTimePercentage < 0) {
            if (expectedTime == 0) {
                completedTimePercentage = 100;
            }
    
            completedTimePercentage = Math.min((int) Math.floor((double) completedTime / (double) expectedTime * 100d), 100);
        }
        
        return completedTimePercentage;
    }

    public int getTimeLeftPercentage() {
        return 100 - getCompletedTimePercentage();
    }

    public int getCompletedTests() {
        return completedTests;
    }

    public int getExpectedTests() {
        return expectedTests;
    }

    public float getCompletedTime() {
        return completedTime;
    }

    public float getExpectedTime() {
        return expectedTime;
    }

}
