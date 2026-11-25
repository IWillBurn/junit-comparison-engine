# API and compatibility

`@ComparisonTest` is a class annotation in `org.atpfivt.comparison.api`. Set `source` to a `ComparisonSource<V>` implementation that selects active enum variants. The test class must allow subclassing so the engine can generate its Jupiter test class.

## Method parameters

The engine supplies the current enum constant to a test parameter of the enum type. A variant's `ParameterResolver`s supply any parameters they support. Standard Jupiter resolvers are also available.

For `@ParameterizedTest`, declare argument-set parameters first, followed by parameters supplied by the engine or other Jupiter resolvers. Use `@ComparisonArguments(n)` to declare the number of leading argument-set parameters.

```java
void mul(int a, int b, int expected, Calc calc, TestInfo info) { /* ... */ }
```

The argument source runs once per method for each invocation of the enclosing test class. Every implementation in an input row receives the same source arguments. Local `@MethodSource` methods and `@FieldSource` fields must be static. Method-level parameter resolver extensions can supply parameters to a `@MethodSource` factory.

## Lifecycle

- The engine calls the source's no-argument constructor and reads `variants()` during discovery. Keep discovery free of resource startup. Execution creates a fresh source instance and routes parameter requests to the variant's resolvers.
- A source can implement Jupiter callbacks for the comparison class, a class-template invocation, or an individual test. Use `ComparisonContext.classContext(context)` to find the owning class or class-template invocation store. Register shared `AutoCloseable` resources in the store for Jupiter to close.
- Under the default `PER_METHOD` lifecycle, the test constructor can receive the variant and its arguments. `@BeforeEach`, the test, and `@AfterEach` also have access to them. Under `PER_CLASS`, the constructor runs at class scope. Variant-specific parameters are available in leaf methods and per-test callbacks.

See the [source lifecycle verification](verification/src/test/java/verification/ComparisonSourceTest.java) for an example of per-leaf arguments backed by a shared resource.

## Jupiter annotations and extensions

`@Test`, `@ParameterizedTest`, `@ParameterizedClass`, and `@TestInstance` are supported. Execution annotations such as `@Disabled`, `@Tag`, `@Timeout`, and `@ExtendWith` apply to the generated implementation tests. Composed test annotations and instance-based `@EnabledIf` and `@DisabledIf` methods are covered by the [compatibility tests](verification/src/test/java/verification/AnnotationCompatibilityTest.java).

A comparison class reports a skip with a reason for `@Nested` classes, `@TestFactory` methods, `@RepeatedTest` methods and custom `@TestTemplate` methods.

`@Skip` selects variants by enum constant name on individual test methods. `TestInfo.getDisplayName()` returns `Variant.testName()`. Jupiter metadata exposed through `TestInfo` and `ExtensionContext` can identify generated classes and methods. Runners receive the original method as the test source for navigation.

## Selection and reruns

The test tree follows `class → method → argument set → variant`. An ordinary `@Test` places the variants directly under the method. A `@ParameterizedClass` adds its class invocation above the method.

The Launcher can select classes, methods, and individual nodes by `UniqueId`. A selection that combines all invocations of a parameterized class with individual invocations can execute additional leaves internally. The engine reports the selected nodes to the outer runner. With a parameterized class, method tags become visible when Jupiter materializes its invocations, so a discovery-time inclusion filter can depend on tags on the class itself.

## Runtime compatibility

The build targets JDK 21. The verification suite uses JUnit Jupiter and Platform 6.1.3. The engine uses Jupiter's generated-test metadata and identifier format for reruns, so use the verified version when integrating it. The engine generates test classes in the package of the user test, and a named Java module must open that package for the runtime lookup.
