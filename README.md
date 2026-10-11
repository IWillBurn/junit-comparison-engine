# Вариант `jupiter-descriptors`: дескрипторы Jupiter

Движок сравнения реализаций для JUnit. Один тест прогоняется на нескольких реализациях. Каждая реализация —
отдельный лист **под** набором аргументов:

```
MulTest
├─ mul(int, int, int, Calc)
│  ├─ [1] 2, 3, 6
│  │  ├─ Good ✔
│  │  └─ Buggy ✔
│  └─ [2] 4, 5, 20
│     ├─ Good ✔
│     └─ Buggy ✘ expected: <20> but was: <21>
└─ mulFourByFive(Calc)
   ├─ Good ✔
   └─ Buggy ✘ expected: <20> but was: <21>
```

Это один из трёх вариантов движка. У всех вариантов одинаковые пользовательский API ([API.md](API.md)) и примеры.
Обзор вариантов и их сравнение — в ветке [`main`](https://github.com/IWillBurn/junit-comparison-engine/tree/main).

## Как устроен

- Свой `TestEngine` `comparison-tests` вызывает настоящее обнаружение Jupiter (`JupiterTestEngine.discover`) для
  своего id. Из полученного дерева он берёт только классы с `@ComparisonTest`.
- В этом дереве Jupiter он заменяет узлы методов своими подклассами внутренних дескрипторов Jupiter:
  - `@Test` → контейнер метода, под ним заранее известные листья `Good`, `Buggy`;
  - `@ParameterizedTest` (и любой `@TestTemplate`) → подкласс `TestTemplateTestDescriptor`. Источник аргументов
    обрабатывает сам junit-jupiter-params, но на каждый вызов создаётся контейнер набора, а в нём лист на каждую
    реализацию;
  - лист — подкласс `TestMethodTestDescriptor`, то есть обычный узел теста Jupiter: новый экземпляр класса,
    `@BeforeEach`, расширения, тест, `@AfterEach`. Добавлен только `ParameterResolver` реализации.
- Выполняет дерево исполнитель Jupiter (`JupiterTestEngine.execute`), события шлёт сам Jupiter. Вложенного
  Launcher, дерева-зеркала и перекладывания событий нет.
- Фильтр прячет эти классы от обычного `junit-jupiter`, поэтому каждый тест выполняется один раз.
- Два класса лежат в чужом пакете `org.junit.jupiter.engine.descriptor` (split package): только так доступны
  package-private конструкторы Jupiter.

Код движка (`comparison-engine/src/main`): ≈ 1100 строк, без комментариев и пустых строк ≈ 840.

Surefire называет листья `MulTest.mul(int, int, int, Calc)[2][2]` и `MulTest.mulFourByFive(Calc)[2]` (номер набора,
номер реализации). Фильтр `-Dtest=MulTest#mul` работает.

## Запуск

Нужны JDK 21 и Maven 3.9+. JUnit 6.1.3. Из корня:

```
mvn test
```

В `examples` падения `Buggy` задуманы, там стоит `testFailureIgnore`. Тесты `verification` должны быть зелёными.

## IntelliJ IDEA

Откройте корневой `pom.xml` как проект и запускайте классы из `examples/src/test/java/examples`:

- **`MulTest`** — дерево выше: `@ParameterizedTest` и `@Test`, падает только `Buggy` на 4 × 5.
- **`LifecycleTest`** — в выводе каждого листа свои `@BeforeEach`/`@AfterEach` и новый экземпляр (#1..#6).
  `@BeforeAll` выполняется один раз. Порядок естественный: `Good`, `Buggy` для каждого набора.
- **`PlainLifecycleTest`** — тот же класс в обычном Jupiter, для сравнения.

Любой узел (лист, набор аргументов, метод) перезапускается из дерева результатов, работает и **Rerun Failed Tests**
(IDE передаёт UniqueId узлов). Перезапуск **точный**: выполняются только выбранные листья.

Проверено собственным раннером IntelliJ (`JUnitStarter -junit6`, `junit6-rt.jar` из IDEA 2025.2.6.3, всё на одном
classpath): запуск классов и метода `mul`, перезапуск набора `[2]` и двух упавших листьев файлом UniqueId, как это
делает IDE. В сообщениях `##teamcity` ожидаемое дерево, ни одного потерянного или `ignored` листа, у листьев
`locationHint` ведёт в метод теста.

При запуске одного `LifecycleTest` IntelliJ показывает строку `@BeforeAll` в конце вывода, хотя выполняется она
первой. Объяснение — в комментарии `LifecycleTest`.

## Модуль `verification`

`ComparisonEngineTest` запускает примеры через Launcher API, как IDE. Проверяется:

- дерево `MulTest` со статусами и уникальные строки CI на каждый лист;
- в `LifecycleTest` `@BeforeAll` выполняется 1 раз, а `@BeforeEach` и новый экземпляр — на каждый из 6 листов;
- запуск одного метода;
- перезапуск листьев и набора аргументов по UniqueId;
- обычный Jupiter не выполняет сравнительные классы повторно, а Jupiter внутри движка не выполняет повторно
  обычные классы того же запуска;
- соблюдается порядок событий: родитель стартует раньше детей и заканчивается после них.

`VariantPropertiesTest` фиксирует одним списком событий то, чем различаются варианты: когда выполняются
`@BeforeAll` и источник аргументов, порядок листьев, какую реализацию получает конструктор и что видит `TestInfo`.
Здесь список такой:

```
@BeforeAll
source
param 1 Good, constructor: Good, TestInfo: Good
param 1 Buggy, constructor: Buggy, TestInfo: Buggy
param 2 Good, constructor: Good, TestInfo: Good
param 2 Buggy, constructor: Buggy, TestInfo: Buggy
plain Good, constructor: Good, TestInfo: Good
plain Buggy, constructor: Buggy, TestInfo: Buggy
```

`ExtraFeaturesTest` подтверждает то, что вариант умеет сверх минимума: `@ParameterizedClass`, `@RepeatedTest`,
`@Nested`, автоматически закрываемые аргументы, `@TestInstance(PER_CLASS)`, пропуск `@TestFactory` с причиной,
перезапуск листьев внутри `@ParameterizedClass` и `@Nested`, `IterationSelector`, лист вместе со своим классом.

Классы из `fixtures` запускаются только из этих тестов.

## Особенности варианта

### Как вариант справляется с проблемами `class-template`

1. **`TestInfo` и `ExtensionContext` видят лист.** `TestInfo.getDisplayName()` возвращает `Good` или `Buggy`.
   Родитель контекста листа — контекст набора аргументов (`[2] 4, 5, 20`), выше — метод и класс, как в дереве.
2. **Источник аргументов вычисляется один раз.** Его выполняет сам junit-jupiter-params внутри Jupiter, а
   реализации одного набора получают одни и те же аргументы. Обратная сторона: это **одни и те же объекты**.
   Если `Good` изменит изменяемый аргумент, `Buggy` увидит изменение. Аргументы с `AutoCloseable` закрываются
   один раз, после последней реализации набора.
3. **Порядок естественный.** Для каждого набора выполняются `Good`, потом `Buggy`, затем следующий набор.
   Контейнеры закрываются сразу после своих листьев, их время не завышено.

Кроме того:

- **`@ParameterizedClass` сочетается.** Уровень реализации — самый нижний, поэтому Jupiter сам копирует узлы
  методов в каждый вызов класса. Для этого у наших узлов есть `withUniqueId`. Конструктор получает и аргумент
  класса, и реализацию.
- `@BeforeAll` выполняется один раз на класс.
- `@TestInstance(PER_CLASS)` работает, но экземпляр один на все листья и создаётся раньше листьев. Поэтому
  конструктор реализацию получить не может: с параметром `Calc` класс падает с `No ParameterResolver registered`.
  Методы реализацию получают.
- Работают `@RepeatedTest` и любой `@TestTemplate`, а также `@Nested`. Конструктор внешнего класса в `@Nested`-тесте
  реализацию не получает: внешний экземпляр Jupiter создаёт без расширений листа.
- `@Tag`-фильтр работает как в Jupiter: он отбирает методы целиком, листья наследуют теги метода и класса.
- `@TestFactory` не поддерживается: в дереве это пропущенный лист с причиной, метод не выполняется.
- Перезапуск по UniqueId точный. Jupiter не знает сегмент `[implementation:X]`, поэтому движок переводит селекторы:
  - у листа `@ParameterizedTest` сегмент переименовывается в `test-template-invocation`. Дальше лист и его набор
    отбирает фильтр самого Jupiter (`DynamicDescendantFilter`);
  - у листа `@Test` в Jupiter уходит UniqueId метода, а движок запоминает имена реализаций. Если тот же метод целиком
    выбран другим селектором (класс, метод), выполняются все листья. Это выясняет второе, «тихое» обнаружение
    Jupiter по остальным селекторам. Оно нужно только в таком смешанном запросе.

### Используемый внутренний API (JUnit 6.1.3)

Всё перечисленное помечено `@API(status = INTERNAL)` или недоступно извне пакета. Без указания пакета —
`org.junit.jupiter.engine.descriptor`.

| Тип | Что используется |
|---|---|
| `org.junit.jupiter.engine.JupiterTestEngine` | `new`, `discover(…)`, `execute(…)` |
| `JupiterEngineDescriptor` | приведение типа, `getConfiguration()`; корень для исполнителя Jupiter |
| `JupiterTestDescriptor` | package-private конструктор `(UniqueId, String, TestSource, JupiterConfiguration)` и package-private поле `configuration` (через split package); переопределяются `prepare`, `execute`, `after`, `cleanUp`, `shouldBeSkipped`, `withUniqueId`, `getLegacyReportingIndex` |
| `TestMethodTestDescriptor` | package-private конструктор `(UniqueId, String, Class, Method, JupiterConfiguration, VoidMethodInterceptorCall)` (через split package); `SEGMENT_TYPE`; переопределяются `populateNewExtensionRegistry`, `withUniqueId`, `getLegacyReportingIndex`, `getExclusiveResources` |
| `TestTemplateTestDescriptor` | публичный конструктор, свой `prepare` (контекст шаблона); переопределяются `execute`, `withUniqueId`, `getDynamicDescendantFilter`, `mayRegisterTests` |
| `MethodBasedTestDescriptor` | `getTestClass()`, `getTestMethod()`, `getEnclosingTestClasses()` |
| `TestTemplateInvocationTestDescriptor` | `SEGMENT_TYPE` |
| `ClassBasedTestDescriptor`, `ClassTemplateInvocationTestDescriptor`, `TestFactoryTestDescriptor` | проверки `instanceof`, `getTestClass()` |
| `DynamicDescendantFilter` | `copy(…)`, `test(…)`, `withoutIndexFiltering()` |
| `AbstractExtensionContext` (package-private класс) | подкласс для контекста набора аргументов (через split package) |
| `org.junit.jupiter.engine.execution.JupiterEngineExecutionContext` | `extend()…build()`, `getExtensionRegistry()`, `getExtensionContext()`, `getExecutionListener()`, `getConfiguration()`, `getLauncherStoreFacade()` |
| `org.junit.jupiter.engine.execution.InterceptingExecutableInvoker.ReflectiveInterceptorCall.VoidMethodInterceptorCall` | ссылки `InvocationInterceptor::interceptTestMethod` / `interceptTestTemplateMethod` |
| `org.junit.jupiter.engine.execution.LauncherStoreFacade` | передаётся в конструктор контекста |
| `org.junit.jupiter.engine.extension.MutableExtensionRegistry` | `createRegistryFrom(…)`, `registerExtension(Extension, Object)` |
| `org.junit.jupiter.engine.config.JupiterConfiguration` | передаётся в конструкторы |
| `org.junit.jupiter.engine.support.JupiterThrowableCollectorFactory` | `createThrowableCollector()` |
| `org.junit.platform.engine.ExecutionRequest` | `create(…)` с шестью параметрами (`INTERNAL` с 6.0); `getStore()`, `getCancellationToken()` — `EXPERIMENTAL` |

Split package — два класса без логики: `ComparisonLeafDescriptor` (лист) и `ComparisonNodeDescriptor` (контейнер
набора, пропущенный лист и контекст набора).

### Цена варианта

- **Привязка к версии.** Внутренний API меняется без предупреждения. Код собран и проверен только на 6.1.3, на
  другой версии возможны `NoSuchMethodError`/`AbstractMethodError`. Каждое обновление JUnit нужно перепроверять.
- **Один загрузчик классов.** Split package работает, только если JUnit и библиотека загружены одним загрузчиком:
  Maven Surefire, IntelliJ, `java -cp … ConsoleLauncher`. У `junit-platform-console-standalone --class-path`
  загрузчики разные. Движок это обнаруживает и сообщает критическую ошибку обнаружения: сравнительные классы не
  выполняются, остальные движки работают (проверено). На module path не работает совсем.
- **Строки CI без имени реализации:** `mul(int, int, int, Calc)[2][2]`, `mulFourByFive(Calc)[2]`. Их строит сам
  Jupiter (`getLegacyReportingName()` и `getLegacyReportingBaseName()` — `final`), а индекс может быть только
  числом.
- **Повтор кода Jupiter.** Цикл по провайдерам из package-private `TemplateExecutor` (проверка «0 вызовов» и
  фильтр) и подготовка вызова (`prepareInvocation`, расширения вызова) повторены в своём коде, около 60 строк. При
  изменениях в Jupiter они могут разойтись с оригиналом.
- **Двойное обнаружение.** И `junit-jupiter`, и `comparison-tests` выполняют обнаружение Jupiter по всему запросу.
  Сканирование classpath удваивается, а предупреждения обнаружения о сравнительных классах приходят от обоих
  движков. Изредка нужно третье, «тихое» обнаружение (см. перезапуск листа `@Test` выше).
- **Два движка.** Сравнительные классы выполняются отдельно от обычных: сначала один движок, потом другой. Корневой
  `ExtensionContext` у них разный, поэтому общий ресурс в корневом `Store` создаётся дважды. Если выбрать только
  движок `junit-jupiter`, сравнительные классы молча пропадут: их прячет фильтр.
- **Ошибка в одном `@ComparisonTest`** (пустой `value()`, нет конструктора без аргументов, одинаковые простые
  имена) — критическая ошибка обнаружения. В этом запуске не выполняется ни один сравнительный класс, как при
  критических ошибках самого Jupiter.
