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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.pipeline.JUnitResultsStepExecution;
import hudson.tasks.test.PipelineTestDetails;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import com.google.common.base.Predicate;

import static java.util.Objects.requireNonNull;

public class PipelineRealtimeTestResultAction extends AbstractRealtimeTestResultAction {

    private static final Logger LOGGER = Logger.getLogger(PipelineRealtimeTestResultAction.class.getName());

    final String id;
    private final String node;
    private final String workspace;
    private final boolean keepLongStdio;
    private final String glob;
    @CheckForNull
    private transient final StepContext context;
    private final Long parseInterval;

    PipelineRealtimeTestResultAction(
            String id,
            FilePath ws,
            boolean keepLongStdio,
            String glob,
            StepContext context,
            Long parseInterval
    ) {
        this.id = id;
        node = FilePathUtils.getNodeName(ws);
        workspace = ws.getRemote();
        this.keepLongStdio = keepLongStdio;
        this.glob = glob;
        this.context = context;
        this.parseInterval = parseInterval;
    }

    @Override
    public String getDisplayName() {
        if (node.isEmpty()) {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_master();
        } else {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_(node);
            // TODO include the branch or stage name (nearest enclosing LabelAction), as in jenkinsci/workflow-durable-task-step-plugin#2
        }
    }

    @Override
    public String getUrlName() {
        return "realtimeTestReport-" + id;
    }

    @Override
    protected long getParseInterval() {
        return parseInterval != null ? parseInterval.longValue() : super.getParseInterval();
    }

    @Override
    protected TestResult parse() throws IOException, InterruptedException {
        FilePath ws = FilePathUtils.find(node, workspace);
        if (ws != null && ws.isDirectory()) {
            LOGGER.log(Level.FINE, "parsing {0} in {1} on node {2} for {3}", new Object[] {glob, workspace, node, run});

            FlowNode node = getFlowNode();
            TaskListener listener = getTaskListener();

            PipelineTestDetails pipelineTestDetails = null;
            if (node != null) {
                List<FlowNode> enclosingBlocks = JUnitResultsStepExecution
                        .getEnclosingStagesAndParallels(requireNonNull(node));

                pipelineTestDetails = new PipelineTestDetails();
                pipelineTestDetails.setNodeId(id);
                pipelineTestDetails.setEnclosingBlocks(JUnitResultsStepExecution.getEnclosingBlockIds(enclosingBlocks));
                pipelineTestDetails.setEnclosingBlockNames(JUnitResultsStepExecution.getEnclosingBlockNames(enclosingBlocks));
            }

            return new JUnitParser(keepLongStdio, true)
                    .parseResult(glob, this.run, pipelineTestDetails, ws, null, listener);
        } else {
            throw new AbortException("skipping parse in nonexistent workspace for " +  run);
        }
    }

    @CheckForNull
    @Override
    protected TestResult findPreviousTestResult() throws IOException, InterruptedException {
        TestResult testResult = null;
        
        FlowNode node = getFlowNode();
        if (node != null) {
            List<FlowNode> enclosingBlocks = JUnitResultsStepExecution.getEnclosingStagesAndParallels(node);
            List<String> blockNames = JUnitResultsStepExecution.getEnclosingBlockNames(enclosingBlocks);
            String blockName = !blockNames.isEmpty() ? blockNames.get(0) : null;
    
            testResult = findPreviousTestResult(run, blockName);
        }
        
        return testResult;
    }
    
    // lots of boilerplate code to get access to the flow node and listener but without saving
    // the StepContext via xstream in case of restarts, we default to using step context if it's available though
    
    @CheckForNull
    private FlowNode getFlowNode() throws IOException, InterruptedException {
        FlowNode node = null;
        
        if (context != null) {
            node = context.get(FlowNode.class);
        } else {
            FlowExecutionOwner flowOwner = getFlowExecutionOwner(this.run);
            if (flowOwner != null) {
                FlowExecution flowExecution = flowOwner.getOrNull();
                if (flowExecution != null) {
                    node = flowExecution.getNode(id);
                }
            }
        }
        
        return node;
    }
    
    private TaskListener getTaskListener() throws IOException, InterruptedException {
        TaskListener listener = TaskListener.NULL;
        
        if (context != null) {
            listener = context.get(TaskListener.class);
        } else {
            FlowExecutionOwner flowOwner = getFlowExecutionOwner(this.run);
            if (flowOwner != null) {
                listener = flowOwner.getListener();
            }
        }
        
        return listener;
    }

    @CheckForNull
    private static FlowExecutionOwner getFlowExecutionOwner(Run<?, ?> build) {
        FlowExecutionOwner flowOwner = null;
        
        if (build instanceof FlowExecutionOwner.Executable) {
            FlowExecutionOwner.Executable executable = (FlowExecutionOwner.Executable) build;
            flowOwner = executable.asFlowExecutionOwner();
        }
        
        return flowOwner;
    }

    @CheckForNull
    private static TestResult findPreviousTestResult(Run<?, ?> build, final String blockName) {
        TestResult tr = findPreviousTestResult(build);
        if (tr != null && blockName != null) {
            FlowExecutionOwner owner = getFlowExecutionOwner(tr.getRun());
            if (owner != null) {
                try {
                    FlowExecution execution = owner.get();
                    if (execution != null) {
                        DepthFirstScanner scanner = new DepthFirstScanner();
                        FlowNode stageId = scanner.findFirstMatch(execution, new BlockNamePredicate(blockName));
                        if (stageId != null) {
                            tr = ((TestResult) tr).getResultForPipelineBlock(stageId.getId());
                        } else {
                            tr = null;
                        }
                    }
                } catch (IOException e) {
                }
            }
        }
        return tr;
    }

    static class BlockNamePredicate implements Predicate<FlowNode> {
        private final String blockName;
        public BlockNamePredicate(@NonNull String blockName) {
            this.blockName = blockName;
        }
        @Override
        public boolean apply(@Nullable FlowNode input) {
            if (input != null) {
                LabelAction labelAction = input.getPersistentAction(LabelAction.class);
                if (labelAction != null && labelAction instanceof ThreadNameAction) {
                    return blockName.equals(((ThreadNameAction) labelAction).getThreadName());
                }
                return labelAction != null && blockName.equals(labelAction.getDisplayName());
            }
            return false;
        }
    }

}
