package org.jenkinsci.plugins.junitrealtimetestreporter;

import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.given;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import hudson.tasks.junit.TestResult;

@RunWith(MockitoJUnitRunner.class)
public class TestProgressTest {

    @Mock
    private TestResult realtimeResult;
    
    @Test
    public void estimatedRemainingTimeInMinutes() throws Exception {
        int expectedTests = 1;
        float expectedTime = 3 * 60;
        
        given(realtimeResult.getDuration()).willReturn(60f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals("2 min 0 sec", testProgress.getEstimatedRemainingTime());
    }
    
    @Test
    public void estimatedRemainingTimeInSeconds() throws Exception {
        int expectedTests = 1;
        float expectedTime = 65;
        
        given(realtimeResult.getDuration()).willReturn(6f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals("59 sec", testProgress.getEstimatedRemainingTime());
    }
    
    @Test
    public void estimatedRemainingTimeWhenCurrentTestsAreLonger() throws Exception {
        int expectedTests = 1;
        float expectedTime = 65;
        
        given(realtimeResult.getDuration()).willReturn(66f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals("0 sec", testProgress.getEstimatedRemainingTime());
    }
    
    @Test
    public void estimatedRemainingTimeWhenExpectedIsZero() throws Exception {
        int expectedTests = 1;
        float expectedTime = 0;
        
        given(realtimeResult.getDuration()).willReturn(5f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals("0 sec", testProgress.getEstimatedRemainingTime());
    }
    
    @Test
    public void completedTestsPercentage() throws Exception {
        int expectedTests = 9;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(3);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(33, testProgress.getCompletedTestsPercentage());
    }
    
    @Test
    public void completedTestsPercentageWhenMoreTests() throws Exception {
        int expectedTests = 9;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(10);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getCompletedTestsPercentage());
    }
    
    @Test
    public void completedTestsPercentageWhenExpectedTestsIsZero() throws Exception {
        int expectedTests = 0;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(1);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getCompletedTestsPercentage());
    }
    
    @Test
    public void completedTestsPercentageWhenCurrentTestsIsZero() throws Exception {
        int expectedTests = 2;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(0);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getCompletedTestsPercentage());
    }
    
    @Test
    public void testsLeftPercentage() throws Exception {
        int expectedTests = 9;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(3);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(67, testProgress.getTestsLeftPercentage());
    }
    
    @Test
    public void testsLeftPercentageWhenMoreTests() throws Exception {
        int expectedTests = 9;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(10);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getTestsLeftPercentage());
    }
    
    @Test
    public void testsLeftPercentageWhenExpectedTestsIsZero() throws Exception {
        int expectedTests = 0;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(1);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getTestsLeftPercentage());
    }
    
    @Test
    public void testsLeftPercentageWhenCurrentTestsIsZero() throws Exception {
        int expectedTests = 2;
        float expectedTime = 30;
        
        given(realtimeResult.getTotalCount()).willReturn(0);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getTestsLeftPercentage());
    }
    
    @Test
    public void completedTimePercentage() throws Exception {
        int expectedTests = 10;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(30f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(33, testProgress.getCompletedTimePercentage());
    }
    
    @Test
    public void completedTimePercentageWhenCurrentTestsAreLonger() throws Exception {
        int expectedTests = 10;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(100f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getCompletedTimePercentage());
    }
    
    @Test
    public void completedTimePercentageWhenExpectedIsZero() throws Exception {
        int expectedTests = 10;
        float expectedTime = 0;
        
        given(realtimeResult.getDuration()).willReturn(30f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getCompletedTimePercentage());
    }
    
    @Test
    public void completedTimePercentageWhenCurrentIsZero() throws Exception {
        int expectedTests = 2;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(0f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getCompletedTimePercentage());
    }
    
    @Test
    public void timeLeftPercentage() throws Exception {
        int expectedTests = 10;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(30f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(67, testProgress.getTimeLeftPercentage());
    }
    
    @Test
    public void timeLeftPercentageWhenCurrentTestsAreLonger() throws Exception {
        int expectedTests = 10;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(100f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getTimeLeftPercentage());
    }
    
    @Test
    public void timeLeftPercentageWhenExpectedIsZero() throws Exception {
        int expectedTests = 10;
        float expectedTime = 0;
        
        given(realtimeResult.getDuration()).willReturn(30f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(0, testProgress.getTimeLeftPercentage());
    }
    
    @Test
    public void timeLeftPercentageWhenCurrentIsZero() throws Exception {
        int expectedTests = 2;
        float expectedTime = 90;
        
        given(realtimeResult.getDuration()).willReturn(0f);
        
        TestProgress testProgress = new TestProgress(expectedTests, expectedTime, realtimeResult);

        assertEquals(100, testProgress.getTimeLeftPercentage());
    }

}
