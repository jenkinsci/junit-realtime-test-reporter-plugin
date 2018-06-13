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

import static org.junit.Assert.*;
import static org.mockito.BDDMockito.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import hudson.Main;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;

@RunWith(MockitoJUnitRunner.class)
public class AbstractRealtimeTestResultActionTest  {

    @Spy
    private AbstractRealtimeTestResultAction action;

    @Mock
    private Run<?, ?> run;

    @Before
    public void init() throws Exception {
        Main.isUnitTest = true;
        action.run = run;

        given(action.parse()).willReturn(new TestResult());
    }

    @Test
    public void getPreviousResultsOnlyOnce() throws Exception {
        final long parseInterval = 0;
        given(action.getParseInterval()).willReturn(parseInterval);
        int noTimes = 3;

        for( int i = 0; i < noTimes; i++) {
            action.getResult();
        }

        verify(action, times(noTimes)).parse();
        verify(action, times(1)).findPreviousTestResult();
    }

    @Test
    public void progressIsNullWithoutPreviousTestResults() throws Exception {
        action.getResult();
        assertNull(action.getTestProgress());
        verify(action).findPreviousTestResult();
    }

    @Test
    public void progressIsCreatedOnCallToGetResults() throws Exception {
        given(action.findPreviousTestResult()).willReturn(new TestResult());

        assertNull(action.getTestProgress());

        action.getResult();
        assertNotNull(action.getTestProgress());
    }
}
