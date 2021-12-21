# JUnit Realtime Test Reporter

## Introduction

Publishes test results while tests run, rather than waiting for completion like that JUnit plugin does.

## Getting started

### Pipeline

```groovy
realtimeJUnit('**/target/surefire-reports/TEST-*.xml') {
    sh 'mvn -Dmaven.test.failure.ignore=true clean verify'
}
```

### Freestyle

Tick "Visualize test results in real time" in Job configuration.
Builds with JUnit publisher and Maven builds will have an action called "Realtime Test Result" while in progress.

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE.md)
