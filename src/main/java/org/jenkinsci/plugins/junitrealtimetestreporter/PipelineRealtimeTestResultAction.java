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

import hudson.FilePath;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.test.TestResult;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.FilePathUtils;

class PipelineRealtimeTestResultAction extends AbstractRealtimeTestResultAction {

    private static final Logger LOGGER = Logger.getLogger(PipelineRealtimeTestResultAction.class.getName());

    final String id;
    private final String node;
    private final String workspace;
    private final boolean keepLongStdio;
    private final String glob;

    PipelineRealtimeTestResultAction(String id, FilePath ws, boolean keepLongStdio, String glob) {
        this.id = id;
        node = FilePathUtils.getNodeName(ws);
        workspace = ws.getRemote();
        this.keepLongStdio = keepLongStdio;
        this.glob = glob;
    }

    @Override
    public String getDisplayName() {
        if (node.isEmpty()) {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_master();
        } else {
            return Messages.PipelineRealtimeTestResultAction_realtime_test_result_on_(node);
        }
    }

    @Override
    public String getUrlName() {
        return "realtimeTestReport-" + id;
    }

    @Override
    protected TestResult parse() throws IOException, InterruptedException {
        FilePath ws = FilePathUtils.find(node, workspace);
        if (ws != null && ws.isDirectory()) {
            LOGGER.log(Level.FINE, "parsing {0} in {1} on node {2} for {3}", new Object[] {glob, workspace, node, run});
            return new JUnitParser(keepLongStdio, true).parseResult(glob, run, ws, null, null);
        } else {
            LOGGER.log(Level.FINE, "skipping parse in nonexistent workspace for {0}", run);
            return new hudson.tasks.junit.TestResult();
        }
    }

}
