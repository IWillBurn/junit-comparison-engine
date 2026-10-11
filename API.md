# API для пользователя (минимальный прототип)

Общий контракт всех вариантов. Модуль `examples` написан только против него. Пакет `org.atpfivt.comparison.api`.

## `@ComparisonTest`

```java
@Target(TYPE) @Retention(RUNTIME) @Inherited
public @interface ComparisonTest {
    Class<?>[] value();   // классы реализаций, у каждого конструктор без аргументов
}
```

```java
@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
class MulTest {
    @ParameterizedTest @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected, Calc calc) { ... }

    @Test
    void mulFourByFive(Calc calc) { ... }
}
```

- Работает для обычных `@Test` и `@ParameterizedTest` (любой `@*Source`). Методы остаются стандартными, своих
  аннотаций на них нет.
- **Параметр-реализация** — последний параметр метода, тип которого принимает все реализации. Каждый тест получает
  новый объект; `@BeforeEach`/`@AfterEach` того же теста получают тот же объект.
- **Дерево:** `класс > метод > [набор аргументов] > реализация`. У `@Test` реализации лежат прямо под методом. Лист
  (`TEST`) — одна реализация, его имя — простое имя класса (`Good`, `Buggy`). Порядок листьев — порядок в
  `value()`. Имена методов и наборов такие же, как в Jupiter.
- **Жизненный цикл:** для каждого листа отдельно создаётся новый экземпляр класса и выполняются `@BeforeEach`,
  тест и `@AfterEach`.
- **IDE:** запуск класса и метода, перезапуск любого узла (лист, набор, метод) по UniqueId. Источник узлов — метод
  теста, поэтому двойной клик открывает код теста.

## Не входит в контракт

- Порядок выполнения листьев.
- Сколько раз выполняется `@BeforeAll` и вычисляется источник аргументов.
- Что показывают `TestInfo` и `ExtensionContext`.
- Формат UniqueId и строк CI.

## Отличия вариантов

**Только `class-template`:**

- `@ComparisonTest` мета-аннотирован `@ClassTemplate` и `@ExtendWith(ImplementationProvider.class)`.
- Движок называется `comparison-tests`. UniqueId листа:
  `[engine:comparison-tests]/[class-template:C]/[test-template:m(..)]/[test-template-invocation:#2]/[implementation:Buggy]`.
- Строка CI листа: `mul(int, int, int, Calc)[2][Buggy]`.
- Порядок листьев «по реализациям»: сначала все `Good`, потом все `Buggy`. `@BeforeAll` выполняется 1 раз, источник
  аргументов — на каждую реализацию. `TestInfo.getDisplayName()` листа — имя узла Jupiter (`[1] 2, 3, 6`), одно и то
  же у всех реализаций.
- `@RepeatedTest` работает: `rep > repetition 1 of 2 > Good/Buggy`.
- `@ParameterizedClass` и `@TestInstance(PER_CLASS)` не поддерживаются — класс падает с понятной ошибкой.
- `@Nested` и `@TestFactory` не поддерживаются: в дереве это пропущенный лист с причиной, сами тесты не
  выполняются.
- Перезапуск листа выполняет его набор аргументов (или `@Test`-метод) для всех реализаций, лишние результаты
  отбрасываются.

**Только `generated-carrier`:**

- `@ComparisonTest` без мета-аннотаций Jupiter. Движок `comparison-tests` при запуске генерирует класс-носитель
  `C$$Comparison$N extends C`: на каждый метод `@Nested`-класс (для `@ParameterizedTest` — `@ParameterizedClass` с
  источниками метода), в нём `@Test` на каждую реализацию. Выполняет его настоящий Jupiter во вложенном Launcher.
- UniqueId листа: `[engine:comparison-tests]/[class:C]/[method:m(..)]/[argument-set:#2]/[implementation:Buggy]`, у `@Test`
  без `[argument-set]`. Строка CI листа: `mul(int, int, int, Calc)[2][Buggy]`.
- Порядок листьев — по дереву (`Good`, `Buggy` в каждом наборе). `@BeforeAll` — 1 раз, источник — 1 раз на метод.
  `TestInfo.getDisplayName()` листа — имя реализации, `getTestMethod()`/`getTestClass()` — сгенерированные.
  Конструктор класса получает реализацию листа.
- `@ParameterizedClass` на классе: `класс > [вызов класса] > метод > [набор] > реализация`. `@TestInstance(PER_CLASS)`:
  один экземпляр на все листья, реализацию в конструктор передать нельзя.
- `@Nested`, `@TestFactory`, `@RepeatedTest`, `@TestTemplate` и метод без параметра-реализации: пропущенный лист с
  причиной, сами тесты не выполняются.
- Перезапуск по UniqueId точный. Исключение: узлы под `[class-invocation:#k]` (класс с `@ParameterizedClass`).

**Только `jupiter-descriptors`:**

- `@ComparisonTest` — обычная аннотация, без мета-аннотаций Jupiter. Такие классы выполняет свой движок
  `comparison-tests` (корень дерева «Comparison Tests»). Внутри него работают обнаружение и исполнитель настоящего
  Jupiter, а узлы методов заменены подклассами внутренних дескрипторов Jupiter. Обычный `junit-jupiter` эти классы
  не выполняет: их прячет фильтр.
- UniqueId листа: `[engine:comparison-tests]/[class:C]/[test-template:m(..)]/[test-template-invocation:#2]/[implementation:Buggy]`,
  у `@Test`: `[engine:comparison-tests]/[class:C]/[method:m(..)]/[implementation:Buggy]`. Все сегменты, кроме
  `implementation`, — сегменты самого Jupiter.
- Строка CI листа: `mul(int, int, int, Calc)[2][2]`, то есть номер набора и номер реализации в `value()`. Имени
  реализации в строке нет.
- Порядок естественный: `[1] Good`, `[1] Buggy`, `[2] Good`, … Источник аргументов вычисляется один раз,
  `@BeforeAll` выполняется один раз. `TestInfo`/`ExtensionContext` листа видят сам лист (`getDisplayName()` —
  `Good`).
- Работают и вне минимального объёма: любой `@TestTemplate` (например, `@RepeatedTest`), `@Nested`,
  `@ParameterizedClass` (конструктор получает и аргумент класса, и реализацию), `@TestInstance(PER_CLASS)` (один
  экземпляр на класс, поэтому реализацию получают только методы, не конструктор), `@Tag`.
- `@TestFactory` не поддерживается: в дереве это пропущенный лист с причиной, сам метод не выполняется.
- Нужен JUnit ровно 6.1.3. JUnit и библиотека должны загружаться одним загрузчиком классов (один classpath, как в
  Maven Surefire и IntelliJ). Иначе движок сообщает об ошибке обнаружения и ничего не выполняет.
