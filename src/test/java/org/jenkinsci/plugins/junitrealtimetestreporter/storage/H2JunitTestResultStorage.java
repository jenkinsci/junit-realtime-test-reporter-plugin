package org.jenkinsci.plugins.junitrealtimetestreporter.storage;

import com.thoughtworks.xstream.XStream;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.HistoryTestResultSummary;
import hudson.tasks.junit.PackageResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestDurationResultSummary;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultSummary;
import hudson.tasks.junit.TrendTestResultSummary;
import hudson.tasks.test.PipelineTestDetails;
import hudson.util.XStream2;
import io.jenkins.plugins.junit.storage.JunitTestResultStorage;
import io.jenkins.plugins.junit.storage.JunitTestResultStorageDescriptor;
import io.jenkins.plugins.junit.storage.TestResultImpl;
import jenkins.model.Jenkins;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.database.Database;
import org.jenkinsci.plugins.database.GlobalDatabaseConfiguration;
import org.jenkinsci.plugins.database.h2.LocalH2Database;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;
import org.jvnet.hudson.test.TestExtension;

@TestExtension
public class H2JunitTestResultStorage extends JunitTestResultStorage {

    static final String CASE_RESULTS_TABLE = "caseResults";

    private final ConnectionSupplier connectionSupplier = new LocalConnectionSupplier();

    @Override public RemotePublisher createRemotePublisher(Run<?, ?> build) throws IOException {
        try {
            connectionSupplier.connection(); // make sure we start a local server and create table first
        } catch (SQLException x) {
            throw new IOException(x);
        }
        return new RemotePublisherImpl(build.getParent().getFullName(), build.getNumber());
    }

    @Extension
    public static class DescriptorImpl extends JunitTestResultStorageDescriptor {

        @Override
        public String getDisplayName() {
            return "Test SQL";
        }

    }

    @FunctionalInterface
    private interface Querier<T> {
        T run(Connection connection) throws SQLException;
    }
    @Override public TestResultImpl load(String job, int build) {
        return new H2TestResultImpl(connectionSupplier, job, build, null);
    }

    private static class H2TestResultImpl implements TestResultImpl {
        
        private final ConnectionSupplier connectionSupplier;
        private final String job;
        private final int build;
        private final List<String> nodeIds;
        private final String nodeIdsWhereCondition;
        private final Object nodeIdsParameter;
        
        private H2TestResultImpl(ConnectionSupplier connectionSupplier, String job, int build, List<String> nodeIds) {
            this.connectionSupplier = connectionSupplier;
            this.job = job;
            this.build = build;
            this.nodeIds = nodeIds;
            if (nodeIds != null && !nodeIds.isEmpty()) {
                this.nodeIdsWhereCondition = " AND nodeId IN (?)";
                this.nodeIdsParameter = nodeIds.toArray();
            } else {
                this.nodeIdsWhereCondition = " AND 1 = ?";
                this.nodeIdsParameter = 1;
            }
        }
        
        private <T> T query(Querier<T> querier) {
            try {
                Connection connection = connectionSupplier.connection();
                return querier.run(connection);
            } catch (SQLException x) {
                throw new RuntimeException(x);
            }
        }
        private int getCaseCount(String and) {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ?" + nodeIdsWhereCondition + and)) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setObject(3, nodeIdsParameter);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        int anInt = result.getInt(1);
                        return anInt;
                    }
                }
            });
        }

        private List<CaseResult> retrieveCaseResult(String whereCondition) {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT suite, nodeId, package, testname, classname, errordetails, skipped, duration, stdout, stderr, stacktrace FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ?" + nodeIdsWhereCondition + " AND " + whereCondition)) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setObject(3, nodeIdsParameter);
                    try (ResultSet result = statement.executeQuery()) {

                        List<CaseResult> results = new ArrayList<>();
                        Map<String, ClassResult> classResults = new HashMap<>();
                        TestResult parent = new TestResult(this);
                        while (result.next()) {
                            String testName = result.getString("testname");
                            String packageName = result.getString("package");
                            String errorDetails = result.getString("errordetails");
                            String suite = result.getString("suite");
                            String nodeId = result.getString("nodeId");
                            String className = result.getString("classname");
                            String skipped = result.getString("skipped");
                            String stdout = result.getString("stdout");
                            String stderr = result.getString("stderr");
                            String stacktrace = result.getString("stacktrace");
                            float duration = result.getFloat("duration");

                            PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                            pipelineTestDetails.setNodeId(nodeId);
                            SuiteResult suiteResult = new SuiteResult(suite, null, null, pipelineTestDetails);
                            suiteResult.setParent(parent);
                            CaseResult caseResult = new CaseResult(suiteResult, className, testName, errorDetails, skipped, duration, stdout, stderr, stacktrace);
                            ClassResult classResult = classResults.get(className);
                            if (classResult == null) {
                                classResult = new ClassResult(new PackageResult(new TestResult(this), packageName), className);
                            }
                            classResult.add(caseResult);
                            caseResult.setClass(classResult);
                            classResults.put(className, classResult);
                            results.add(caseResult);
                        }
                        classResults.values().forEach(ClassResult::tally);
                        return results;
                    }
                }
            });
        }

        @Override
        public List<PackageResult> getAllPackageResults() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT suite, nodeId, testname, package, classname, errordetails, skipped, duration, stdout, stderr, stacktrace FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ?" + nodeIdsWhereCondition)) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setObject(3, nodeIdsParameter);
                    try (ResultSet result = statement.executeQuery()) {

                        Map<String, PackageResult> results = new HashMap<>();
                        Map<String, ClassResult> classResults = new HashMap<>();
                        TestResult parent = new TestResult(this);
                        while (result.next()) {
                            String testName = result.getString("testname");
                            String packageName = result.getString("package");
                            String errorDetails = result.getString("errordetails");
                            String suite = result.getString("suite");
                            String nodeId = result.getString("nodeId");
                            String className = result.getString("classname");
                            String skipped = result.getString("skipped");
                            String stdout = result.getString("stdout");
                            String stderr = result.getString("stderr");
                            String stacktrace = result.getString("stacktrace");
                            float duration = result.getFloat("duration");

                            PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                            pipelineTestDetails.setNodeId(nodeId);
                            SuiteResult suiteResult = new SuiteResult(suite, null, null, pipelineTestDetails);
                            suiteResult.setParent(parent);
                            CaseResult caseResult = new CaseResult(suiteResult, className, testName, errorDetails, skipped, duration, stdout, stderr, stacktrace);
                            PackageResult packageResult = results.get(packageName);
                            if (packageResult == null) {
                                packageResult = new PackageResult(parent, packageName);
                            }
                            ClassResult classResult = classResults.get(className);
                            if (classResult == null) {
                                classResult = new ClassResult(new PackageResult(parent, packageName), className);
                            }
                            caseResult.setClass(classResult);
                            classResult.add(caseResult);

                            classResults.put(className, classResult);
                            packageResult.add(caseResult);
                            
                            results.put(packageName, packageResult);
                        }
                        classResults.values().forEach(ClassResult::tally);
                        final List<PackageResult> resultList = new ArrayList<>(results.values());
                        resultList.forEach((PackageResult::tally));
                        resultList.sort(Comparator.comparing(PackageResult::getName, String::compareTo));
                        return resultList;
                    }
                }
            });
        }

        @Override
        public List<TrendTestResultSummary> getTrendTestResultSummary() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT build, sum(case when errorDetails is not null then 1 else 0 end) as failCount, sum(case when skipped is not null then 1 else 0 end) as skipCount, sum(case when errorDetails is null and skipped is null then 1 else 0 end) as passCount FROM " +  H2JunitTestResultStorage.CASE_RESULTS_TABLE +  " WHERE job = ? group by build order by build;")) {
                    statement.setString(1, job);
                    try (ResultSet result = statement.executeQuery()) {

                        List<TrendTestResultSummary> trendTestResultSummaries = new ArrayList<>();
                        while (result.next()) {
                            int buildNumber = result.getInt("build");
                            int passed = result.getInt("passCount");
                            int failed = result.getInt("failCount");
                            int skipped = result.getInt("skipCount");
                            int total = passed + failed + skipped;

                            trendTestResultSummaries.add(new TrendTestResultSummary(buildNumber, new TestResultSummary(failed, skipped, passed, total)));
                        }
                        return trendTestResultSummaries;
                    }
                }
            });
        }

        @Override
        public List<TestDurationResultSummary> getTestDurationResultSummary() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT build, sum(duration) as duration FROM " +  H2JunitTestResultStorage.CASE_RESULTS_TABLE +  " WHERE job = ? group by build order by build;")) {
                    statement.setString(1, job);
                    try (ResultSet result = statement.executeQuery()) {

                        List<TestDurationResultSummary> testDurationResultSummaries = new ArrayList<>();
                        while (result.next()) {
                            int buildNumber = result.getInt("build");
                            int duration = result.getInt("duration");

                            testDurationResultSummaries.add(new TestDurationResultSummary(buildNumber, duration));
                        }
                        return testDurationResultSummaries;
                    }
                }
            });
        }

        @Override
        public List<HistoryTestResultSummary> getHistorySummary(int offset) {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT build, sum(duration) as duration, sum(case when errorDetails is not null then 1 else 0 end) as failCount, sum(case when skipped is not null then 1 else 0 end) as skipCount, sum(case when errorDetails is null and skipped is null then 1 else 0 end) as passCount" +
                                " FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE +
                                " WHERE job = ? GROUP BY build ORDER BY build DESC LIMIT 25 OFFSET ?;"
                )) {
                    statement.setString(1, job);
                    statement.setInt(2, 0);
                    try (ResultSet result = statement.executeQuery()) {

                        List<HistoryTestResultSummary> historyTestResultSummaries = new ArrayList<>();
                        while (result.next()) {
                            int buildNumber = result.getInt("build");
                            int duration = result.getInt("duration");
                            int passed = result.getInt("passCount");
                            int failed = result.getInt("failCount");
                            int skipped = result.getInt("skipCount");

                            Job<?, ?> theJob = Jenkins.get().getItemByFullName(getJobName(), Job.class);
                            if (theJob != null) {
                                Run<?, ?> run = theJob.getBuildByNumber(buildNumber);
                                historyTestResultSummaries.add(new HistoryTestResultSummary(run, duration, failed, skipped, passed));
                            }
                        }
                        return historyTestResultSummaries;
                    }
                }
            });
        }

        @Override
        public int getCountOfBuildsWithTestResults() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(DISTINCT build) as count FROM caseResults WHERE job = ?;")) {
                    statement.setString(1, job);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        int count = result.getInt("count");
                        return count;
                    }
                }
            });
        }

        @Override
        public PackageResult getPackageResult(String packageName) {
            // TODO: Filter by nodeIds?
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT suite, nodeId, testname, classname, errordetails, skipped, duration, stdout, stderr, stacktrace FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ? AND package = ?")) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setString(3, packageName);
                    try (ResultSet result = statement.executeQuery()) {

                        PackageResult packageResult = new PackageResult(new TestResult(this), packageName);
                        Map<String, ClassResult> classResults = new HashMap<>();
                        while (result.next()) {
                            String testName = result.getString("testname");
                            String errorDetails = result.getString("errordetails");
                            String suite = result.getString("suite");
                            String nodeId = result.getString("nodeId");
                            String className = result.getString("classname");
                            String skipped = result.getString("skipped");
                            String stdout = result.getString("stdout");
                            String stderr = result.getString("stderr");
                            String stacktrace = result.getString("stacktrace");
                            float duration = result.getFloat("duration");

                            PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                            pipelineTestDetails.setNodeId(nodeId);
                            SuiteResult suiteResult = new SuiteResult(suite, null, null, pipelineTestDetails);
                            suiteResult.setParent(new TestResult(this));
                            CaseResult caseResult = new CaseResult(suiteResult, className, testName, errorDetails, skipped, duration, stdout, stderr, stacktrace);
                            
                            ClassResult classResult = classResults.get(className);
                            if (classResult == null) {
                                classResult = new ClassResult(packageResult, className);
                            }
                            classResult.add(caseResult);
                            classResults.put(className, classResult);
                            caseResult.setClass(classResult);
                            
                            packageResult.add(caseResult);
                        }
                        classResults.values().forEach(ClassResult::tally);
                        packageResult.tally();
                        return packageResult;
                    }
                }
            });

        }
        
        @Override
        public Run<?, ?> getFailedSinceRun(CaseResult caseResult) {
            // TODO: Filter by nodeIds?
            return query(connection -> {
                int lastPassingBuildNumber;
                Job<?, ?> theJob = Objects.requireNonNull(Jenkins.get().getItemByFullName(job, Job.class));
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT build " +
                                "FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " " +
                                "WHERE job = ? " +
                                "AND build < ? " +
                                "AND suite = ? " +
                                "AND package = ? " +
                                "AND classname = ? " +
                                "AND testname = ? " +
                                "AND errordetails IS NULL " +
                                "ORDER BY BUILD DESC " +
                                "LIMIT 1"
                )) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setString(3, caseResult.getSuiteResult().getName());
                    statement.setString(4, caseResult.getPackageName());
                    statement.setString(5, caseResult.getClassName());
                    statement.setString(6, caseResult.getName());
                    try (ResultSet result = statement.executeQuery()) {
                        boolean hasPassed = result.next();
                        if (!hasPassed) {
                            return theJob.getBuildByNumber(1);
                        }
                        
                        lastPassingBuildNumber = result.getInt("build");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT build " +
                                "FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " " +
                                "WHERE job = ? " +
                                "AND build > ? " +
                                "AND suite = ? " +
                                "AND package = ? " +
                                "AND classname = ? " +
                                "AND testname = ? " +
                                "AND errordetails is NOT NULL " +
                                "ORDER BY BUILD ASC " +
                                "LIMIT 1"
                )
                ) {
                    statement.setString(1, job);
                    statement.setInt(2, lastPassingBuildNumber);
                    statement.setString(3, caseResult.getSuiteResult().getName());
                    statement.setString(4, caseResult.getPackageName());
                    statement.setString(5, caseResult.getClassName());
                    statement.setString(6, caseResult.getName());

                    try (ResultSet result = statement.executeQuery()) {
                        result.next();

                        int firstFailingBuildAfterPassing = result.getInt("build");
                        return theJob.getBuildByNumber(firstFailingBuildAfterPassing);
                    }
                }
            });

        }
        
        @Override
        public String getJobName() {
            return job;
        }

        @Override
        public int getBuild() {
            return build;
        }

        @Override
        public List<CaseResult> getFailedTestsByPackage(String packageName) {
            return getByPackage(packageName, "AND errorDetails IS NOT NULL");
        }

        private List<CaseResult> getByPackage(String packageName, String filter) {
            // TODO: Filter by nodeIds?
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT suite, nodeId, testname, classname, errordetails, duration, skipped, stdout, stderr, stacktrace FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ? AND package = ? " + filter)) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setString(3, packageName);
                    try (ResultSet result = statement.executeQuery()) {

                        List<CaseResult> results = new ArrayList<>();
                        while (result.next()) {
                            String testName = result.getString("testname");
                            String errorDetails = result.getString("errordetails");
                            String suite = result.getString("suite");
                            String nodeId = result.getString("nodeId");
                            String className = result.getString("classname");
                            String skipped = result.getString("skipped");
                            String stdout = result.getString("stdout");
                            String stderr = result.getString("stderr");
                            String stacktrace = result.getString("stacktrace");
                            float duration = result.getFloat("duration");

                            PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                            pipelineTestDetails.setNodeId(nodeId);
                            SuiteResult suiteResult = new SuiteResult(suite, null, null, pipelineTestDetails);
                            suiteResult.setParent(new TestResult(this));
                            results.add(new CaseResult(suiteResult, className, testName, errorDetails, skipped, duration, stdout, stderr, stacktrace));
                        }
                        return results;
                    }
                }
            });
        }


        private List<CaseResult> getCaseResults(String column) {
            return retrieveCaseResult(column + " IS NOT NULL");
        }
        
        @Override
        public SuiteResult getSuite(String name) {
            // TODO: Filter by nodeIds?
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT nodeId, testname, package, classname, errordetails, skipped, duration, stdout, stderr, stacktrace FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build = ? AND suite = ?")) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setString(3, name);
                    try (ResultSet result = statement.executeQuery()) {
                        SuiteResult suiteResult = null;
                        TestResult parent = new TestResult(this);
                        while (result.next()) {
                            String nodeId = result.getString("nodeId");
                            String resultTestName = result.getString("testname");
                            String errorDetails = result.getString("errordetails");
                            String packageName = result.getString("package");
                            String className = result.getString("classname");
                            String skipped = result.getString("skipped");
                            String stdout = result.getString("stdout");
                            String stderr = result.getString("stderr");
                            String stacktrace = result.getString("stacktrace");
                            float duration = result.getFloat("duration");

                            if (suiteResult == null) {
                                PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
                                pipelineTestDetails.setNodeId(nodeId);
                                suiteResult = new SuiteResult(name, null, null, pipelineTestDetails);
                                suiteResult.setParent(parent);
                            }
                            CaseResult caseResult = new CaseResult(suiteResult, className, resultTestName, errorDetails, skipped, duration, stdout, stderr, stacktrace);
                            final PackageResult packageResult = new PackageResult(parent, packageName);
                            packageResult.add(caseResult);
                            ClassResult classResult = new ClassResult(packageResult, className);
                            classResult.add(caseResult);
                            caseResult.setClass(classResult);
                            suiteResult.addCase(caseResult);
                        }
                        return suiteResult != null ? suiteResult : new SuiteResult(name, null, null, null);
                    }
                }
            });

        }

        @Override
        public float getTotalTestDuration() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT sum(duration) as duration FROM " +  H2JunitTestResultStorage.CASE_RESULTS_TABLE +  " WHERE job = ? and build = ?" + nodeIdsWhereCondition + ";")) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    statement.setObject(3, nodeIdsParameter);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            return result.getFloat("duration");
                        }
                        return 0f;
                    }
                }
            });
        }

        @Override public int getFailCount() {
            int caseCount = getCaseCount(" AND errorDetails IS NOT NULL");
            return caseCount;
        }
        @Override public int getSkipCount() {
            int caseCount = getCaseCount(" AND skipped IS NOT NULL");
            return caseCount;
        }
        @Override public int getPassCount() {
            int caseCount = getCaseCount(" AND errorDetails IS NULL AND skipped IS NULL");
            return caseCount;
        }
        @Override public int getTotalCount() {
            int caseCount = getCaseCount("");
            return caseCount;
        }

        @Override
        public List<CaseResult> getFailedTests() {
            List<CaseResult> errordetails = getCaseResults("errordetails");
            return errordetails;
        }

        @Override
        public List<CaseResult> getSkippedTests() {
            List<CaseResult> errordetails = getCaseResults("skipped");
            return errordetails;
        }

        @Override
        public List<CaseResult> getSkippedTestsByPackage(String packageName) {
            return getByPackage(packageName, "AND skipped IS NOT NULL");
        }

        @Override
        public List<CaseResult> getPassedTests() {
            List<CaseResult> errordetails = retrieveCaseResult("errordetails IS NULL AND skipped IS NULL");
            return errordetails;
        }

        @Override
        public List<CaseResult> getPassedTestsByPackage(String packageName) {
            return getByPackage(packageName, "AND errordetails IS NULL AND skipped IS NULL");
        }

        @Override
        @CheckForNull
        public TestResult getPreviousResult() {
            return query(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT build FROM " + H2JunitTestResultStorage.CASE_RESULTS_TABLE + " WHERE job = ? AND build < ? ORDER BY build DESC LIMIT 1")) {
                    statement.setString(1, job);
                    statement.setInt(2, build);
                    try (ResultSet result = statement.executeQuery()) {

                        if (result.next()) {
                            int previousBuild = result.getInt("build");
                            // TODO: Is this supposed to filter by node ids?
                            return new TestResult(new H2TestResultImpl(connectionSupplier, job, previousBuild, nodeIds));
                        }
                        return null;
                    }
                }
            });
        }

        @NonNull
        @Override 
        public TestResult getResultByNodes(@NonNull List<String> nodeIds) {
            return new TestResult(new H2TestResultImpl(connectionSupplier, job, build, nodeIds));
        }
    }
    
    private static class RemotePublisherImpl implements RemotePublisher {

        private final String job;
        private final int build;
        // TODO keep the same supplier and thus Connection open across builds, so long as the database config remains unchanged
        private final ConnectionSupplier connectionSupplier;

        RemotePublisherImpl(String job, int build) {
            this.job = job;
            this.build = build;
            connectionSupplier = new RemoteConnectionSupplier();
        }

        @Override public void publish(TestResult result, TaskListener listener) throws IOException {
            try {
                Connection connection = connectionSupplier.connection();
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + CASE_RESULTS_TABLE + " (job, build, suite, nodeId, package, className, testName, errorDetails, skipped, duration, stdout, stderr, stacktrace) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int count = 0;
                    for (SuiteResult suiteResult : result.getSuites()) {
                        for (CaseResult caseResult : suiteResult.getCases()) {
                            statement.setString(1, job);
                            statement.setInt(2, build);
                            statement.setString(3, suiteResult.getName());
                            statement.setString(4, suiteResult.getNodeId());
                            statement.setString(5, caseResult.getPackageName());
                            statement.setString(6, caseResult.getClassName());
                            statement.setString(7, caseResult.getName());
                            String errorDetails = caseResult.getErrorDetails();
                            if (errorDetails != null) {
                                statement.setString(8, errorDetails);
                            } else {
                                statement.setNull(8, Types.VARCHAR);
                            }
                            if (caseResult.isSkipped()) {
                                statement.setString(9, Util.fixNull(caseResult.getSkippedMessage()));
                            } else {
                                statement.setNull(9, Types.VARCHAR);
                            }
                            statement.setFloat(10, caseResult.getDuration());
                            if (StringUtils.isNotEmpty(caseResult.getStdout())) {
                                statement.setString(11, caseResult.getStdout());
                            } else {
                                statement.setNull(11, Types.VARCHAR);
                            }
                            if (StringUtils.isNotEmpty(caseResult.getStderr())) {
                                statement.setString(12, caseResult.getStderr());
                            } else {
                                statement.setNull(12, Types.VARCHAR);
                            }
                            if (StringUtils.isNotEmpty(caseResult.getErrorStackTrace())) {
                                statement.setString(13, caseResult.getErrorStackTrace());
                            } else {
                                statement.setNull(13, Types.VARCHAR);
                            }
                            statement.executeUpdate();
                            count++;
                        }
                    }
                    listener.getLogger().printf("Saved %d test cases into database.%n", count);
                }
            } catch (SQLException x) {
                throw new IOException(x);
            }
        }

    }

    static abstract class ConnectionSupplier { // TODO AutoCloseable

        private transient Connection connection;

        protected abstract Database database();

        protected void initialize(Connection connection) throws SQLException {}

        synchronized Connection connection() throws SQLException {
            if (connection == null) {
                Connection _connection = database().getDataSource().getConnection();
                initialize(_connection);
                connection = _connection;
            }
            return connection;
        }

    }

    static class LocalConnectionSupplier extends ConnectionSupplier {

        @Override protected Database database() {
            return GlobalDatabaseConfiguration.get().getDatabase();
        }

        @Override protected void initialize(Connection connection) throws SQLException {
            boolean exists = false;
            try (ResultSet rs = connection.getMetaData().getTables(null, null, CASE_RESULTS_TABLE, new String[] {"TABLE"})) {
                while (rs.next()) {
                    if (rs.getString("TABLE_NAME").equalsIgnoreCase(CASE_RESULTS_TABLE)) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) {
                try (Statement statement = connection.createStatement()) {
                    // TODO this and joined tables: nodeId, enclosingBlocks, enclosingBlockNames, etc.
                    statement.execute("CREATE TABLE IF NOT EXISTS " + CASE_RESULTS_TABLE + "(job varchar(255), build int, suite varchar(255), nodeId varchar(255), package varchar(255), className varchar(255), testName varchar(255), errorDetails varchar(255), skipped varchar(255), duration numeric, stdout varchar(100000), stderr varchar(100000), stacktrace varchar(100000))");
                }
            }
        }

    }

    /**
     * Ensures a {@link LocalH2Database} configuration can be sent to an agent.
     */
    static class RemoteConnectionSupplier extends ConnectionSupplier implements SerializableOnlyOverRemoting {

        private static final XStream XSTREAM = new XStream2();
        private final String databaseXml;

        static {
            XSTREAM.allowTypes(new Class[] {LocalH2Database.class});
        }

        RemoteConnectionSupplier() {
            databaseXml = XSTREAM.toXML(GlobalDatabaseConfiguration.get().getDatabase());
        }

        @Override protected Database database() {
            return (Database) XSTREAM.fromXML(databaseXml);
        }

    }

}
