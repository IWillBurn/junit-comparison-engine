# JUnit Comparison Engine

Run the same JUnit Jupiter tests against multiple implementations of a contract. Give each implementation a named result in the test tree so you can see which one failed and rerun it directly.

Use the engine to compare a reference implementation with a replacement, test several backends, or check an optimized implementation against the same inputs and assertions.

The [user guide](https://iwillburn.github.io/junit-comparison-engine/) explains the motivation, the API, lifecycle, extension compatibility, and reruns.

## Quick start

You need JDK 21 and Maven 3.9 or later.

```sh
git clone https://github.com/IWillBurn/junit-comparison-engine.git
cd junit-comparison-engine
mvn clean verify
```

The multiplication example runs two input rows against the `GOOD` and `BUGGY` variants returned by `CalcSource`. [MulTest.java](examples/src/test/java/examples/MulTest.java) · [CalcSource.java](examples/src/test/java/examples/CalcSource.java)

```java
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ComparisonTest(source = CalcSource.class)
class MulTest {
    @ParameterizedTest(quoteTextArguments = false)
    @ComparisonArguments(3)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected, Calc calc) {
        assertEquals(expected, calc.mul(a, b));
    }
}
```

Each input row has a separate result for each implementation. The example deliberately fails on the second row for `Buggy`, and the verification suite checks that failure.

```text
mul(int, int, int, Calc)
├── [1] 2, 3, 6
│   ├── Good  ✓
│   └── Buggy ✓
└── [2] 4, 5, 20
    ├── Good  ✓
    └── Buggy ✗ expected: <20> but was: <21>
```

You can also open the root `pom.xml` in IntelliJ IDEA and run `MulTest` from the gutter.

## Use in a Maven project

The current version is a source-built snapshot. Install it in your local Maven repository from the checkout.

```sh
mvn -pl engine -am install
```

Add the engine as a test dependency. Use the JUnit BOM to keep the Jupiter and Platform modules on **6.1.3**, the version used by this project.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.junit</groupId>
            <artifactId>junit-bom</artifactId>
            <version>6.1.3</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.atpfivt.comparison</groupId>
        <artifactId>comparison-engine</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Place your comparison class under `src/test/java` and run `mvn test` with Maven Surefire. This project uses Surefire 3.5.4. The engine is discovered automatically on the test classpath.

## Supply variants and arguments

**`@ComparisonTest(source = MySource.class)`** runs the constants selected by `ComparisonSource<V>.variants()`. The source supplies Jupiter parameter resolvers for each selected variant. [ComparisonSource.java](engine/src/main/java/org/atpfivt/comparison/api/ComparisonSource.java)

Tests can receive the current variant as a parameter typed as the enum, as `Variant`, or as an interface of your own that extends `Variant`; a parameterized test takes it after its argument-set parameters. [VariantTest.java](examples/src/test/java/examples/VariantTest.java)

```java
@Test
void mulFourByFive(CalcVariant variant, Calc calc) {
    assertEquals(variant == BUGGY ? 21 : 20, calc.mul(4, 5));   // a known difference, pinned
}

@Test
void reportsTheVariant(Variant variant, TestInfo info) {
    assertEquals(variant.testName(), info.getDisplayName());
}
```

To skip a variant on a method, use the library's `@Skip` with the constant's name, or declare a type-safe annotation for your enum once and mark it with `@SkipMarker`. The engine reads the annotation's `value()` and, by convention, an optional `description()`. [Skip.java](examples/src/test/java/examples/Skip.java) · [SkipTest.java](examples/src/test/java/examples/SkipTest.java)

```java
@Test
@Skip("ORACLE")                                            // org.atpfivt.comparison.api.Skip, by name
@Skip(value = "MYSQL", description = "this type is buggy in MySQL")
void jsonColumn(Database db) { ... }
```

```java
@SkipMarker
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Skip.List.class)
public @interface Skip {                                   // the project's own, typed with its enum
    Database[] value();
    String description() default "";

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List { Skip[] value(); }
}
```

```java
@Test
@Skip(value = ORACLE, description = "this type is not supported on Oracle")
@Skip(value = MYSQL, description = "this type is buggy in MySQL")
void jsonColumn(Database db) { ... }
```

`value()` may be one constant or an array, typed or named. The description is reported as the skip reason; without one the reason names the annotation. An unknown name or a constant of another enum invalidates the method with a reported reason. Java only repeats an annotation through a container, so the nested `List` is needed for one description per variant, and a repeatable annotation and its enum must be public; an array `value()` with a shared description needs no container.

A source can implement Jupiter `BeforeAllCallback` and `AfterAllCallback` for the comparison class, the class-template invocation callbacks for each invocation of a parameterized class, and `BeforeEachCallback` and `AfterEachCallback` for individual tests. Use the Jupiter store to own closeable resources. `ComparisonContext.classContext(context)` locates the store's owning class or class-template invocation from a test or its resolver.

For parameter order, resource lifetime, and extension behavior, see the [user guide](https://iwillburn.github.io/junit-comparison-engine/).

## How it works

```mermaid
flowchart LR
    A["<b>Runner</b><br/><br/>Selects tests"] --> B["<b>Discovery</b><br/><br/>Builds the test tree"]
    B --> C["<b>Generation</b><br/><br/>Creates nested tests"]
    C --> D["<b>Jupiter</b><br/><br/>Runs selected variants"]
    D --> E["<b>Reporting</b><br/><br/>Maps results to source"]
```

The engine generates a subclass of the test class and delegates execution to Jupiter. Jupiter handles parameter sources, lifecycle callbacks, and extensions on the generated tests. The engine reports results against the original test methods so runners can navigate to the test and select individual implementations. Extensions that inspect test metadata can see the generated classes and methods.

## Contributing

The [`engine`](engine) module contains the API and the JUnit Platform engine. The [`examples`](examples) module demonstrates behavior, and [`verification`](verification) checks test trees, lifecycle, extension compatibility, and reruns through the Launcher API. The [`doc`](doc) module builds the user guide with Asciidoctor; `mvn -pl doc verify` writes it to `doc/target/generated-docs/index.html`, and the build workflow publishes it to GitHub Pages on every push to `main`.

Run `mvn clean verify` before submitting a change. For a bug report, include the JDK and JUnit versions, the runner, and a minimal test that reproduces the behavior. [GitHub Issues](https://github.com/IWillBurn/junit-comparison-engine/issues)
