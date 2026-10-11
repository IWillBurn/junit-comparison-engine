package org.atpfivt.comparison.engine;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Builder.MethodDefinition;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.jar.asm.Opcodes;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.Member;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.FieldSources;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.MethodSources;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Generates, at runtime, the carrier class of a {@code @ComparisonTest} class, in the user's package and class
 * loader:
 *
 * <pre>
 * &#64;DisplayName("MulTest") &#64;ExtendWith(ImplementationResolver.class) &#64;TestClassOrder(OrderAnnotation.class)
 * public class MulTest$$Comparison$1 extends MulTest {
 *     MulTest$$Comparison$1(...) { super(...); }                       // the user's constructor, imitated
 *
 *     &#64;Nested &#64;Order(1) &#64;DisplayName("mul(int, int, int, Calc)") &#64;TestMethodOrder(OrderAnnotation.class)
 *     &#64;ParameterizedClass(quoteTextArguments = false) &#64;CsvSource({"2, 3, 6", "4, 5, 20"})  // from the method
 *     public class mul {
 *         mul(int a, int b, int expected) { ... }                       // the arguments of one argument set
 *         &#64;Test &#64;Order(1) &#64;DisplayName("Good") &#64;ImplementationLeaf(Calc.Good.class)
 *         void Good(Calc calc) { MulTest$$Comparison$1.this.mul(a, b, expected, calc); }
 *         &#64;Test &#64;Order(2) &#64;DisplayName("Buggy") &#64;ImplementationLeaf(Calc.Buggy.class)
 *         void Buggy(Calc calc) { ... }
 *     }
 *     &#64;Nested &#64;Order(2) &#64;DisplayName("mulFourByFive(Calc)") ...
 *     public class mulFourByFive { &#64;Test ... void Good(Calc calc) { ... } ... }
 * }
 * </pre>
 *
 * The user's test methods are not overridden: the nested Launcher selects only the generated {@code @Nested} classes
 * (NestedClassSelector / IterationSelector), so the inherited test methods and the user's own {@code @Nested}
 * classes are never discovered there. A carrier is generated per execution and contains only what is selected.
 */
final class CarrierGenerator {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** One generated nested class: a user method, the implementations to run, the argument sets (empty = all). */
    record Spec(Member member, List<Implementation> implementations, List<Integer> argumentSets) {
    }

    /** A generated nested class and its leaf methods (method name → implementation). */
    record NestedClass(Class<?> type, Spec spec, Map<String, Implementation> leaves) {
    }

    record Carrier(Class<?> type, List<NestedClass> nestedClasses) {
    }

    private record Param(TypeDefinition type, String name, int modifiers, Annotation[] annotations) {
    }

    private CarrierGenerator() {
    }

    static Carrier generate(ComparisonClass model, List<Spec> specs) throws ReflectiveOperationException {
        Class<?> userClass = model.testClass;
        String carrierName = userClass.getName() + "$$Comparison$" + COUNTER.incrementAndGet();
        DynamicType.Builder<?> carrier = new ByteBuddy()
                .subclass(TypeDescription.ForLoadedType.of(userClass),
                    ConstructorStrategy.Default.IMITATE_SUPER_CLASS.withInheritedAnnotations())
                .name(carrierName)
                .annotateType(carrierAnnotations(model));
        // the nested classes refer to the carrier, the carrier lists the nested classes: describe the carrier first
        // (made once without its member types), then make it again with them
        TypeDescription carrierType = carrier.make().getTypeDescription();

        List<DynamicType.Unloaded<Object>> nested = new ArrayList<>();
        List<Map<String, Implementation>> leaves = new ArrayList<>();
        Set<String> simpleNames = new HashSet<>();
        for (int i = 0; i < specs.size(); i++) {
            Spec spec = specs.get(i);
            String simpleName = spec.member().method().getName();
            for (int n = 2; !simpleNames.add(simpleName); n++) {
                simpleName = spec.member().method().getName() + "_" + n;
            }
            Map<String, Implementation> leafNames = new LinkedHashMap<>();
            nested.add(nestedClass(carrierType, carrierName + "$" + simpleName, spec, i + 1, leafNames, userClass));
            leaves.add(leafNames);
        }

        carrier = carrier.declaredTypes(nested.stream().map(DynamicType::getTypeDescription)
                .toArray(TypeDescription[]::new));
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(userClass, MethodHandles.lookup());
        Map<TypeDescription, Class<?>> loaded = carrier.make().include(nested)
                .load(userClass.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(lookup)).getAllLoaded();

        List<NestedClass> nestedClasses = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            nestedClasses.add(new NestedClass(loaded.get(nested.get(i).getTypeDescription()), specs.get(i),
                leaves.get(i)));
        }
        return new Carrier(loaded.get(carrierType), nestedClasses);
    }

    private static List<AnnotationDescription> carrierAnnotations(ComparisonClass model) {
        List<AnnotationDescription> result = new ArrayList<>();
        result.add(AnnotationDescription.Builder.ofType(DisplayName.class).define("value", model.displayName).build());
        result.add(AnnotationDescription.Builder.ofType(ExtendWith.class)
                .defineTypeArray("value", ImplementationResolver.class).build());
        result.add(AnnotationDescription.Builder.ofType(TestClassOrder.class)
                .define("value", ClassOrderer.OrderAnnotation.class).build());
        // inherited annotations (@ExtendWith, @Tag, @TestInstance, @ParameterizedClass and its sources ...) reach the
        // subclass by themselves; the others (@Disabled, conditions ...) are copied
        for (Annotation a : model.testClass.getDeclaredAnnotations()) {
            Class<? extends Annotation> type = a.annotationType();
            if (!type.isAnnotationPresent(Inherited.class) && targets(type, ElementType.TYPE) && !ignored(type)
                    && type != ComparisonTest.class && type != DisplayName.class) {
                result.add(AnnotationDescription.ForLoadedAnnotation.of(a));
            }
        }
        return result;
    }

    private static DynamicType.Unloaded<Object> nestedClass(TypeDescription carrierType, String name, Spec spec,
            int order, Map<String, Implementation> leafNames, Class<?> userClass) throws NoSuchMethodException {
        Member member = spec.member();
        Method method = member.method();
        Parameter[] parameters = method.getParameters();
        // @ParameterizedTest: the parameters before the implementation are the arguments of the argument set
        // (constructor parameters of the @ParameterizedClass); the others are leaf parameters resolved by Jupiter
        int argumentCount = member.parameterized() ? member.implementationIndex() : 0;
        boolean named = parameters.length > 0 && parameters[0].isNamePresent();

        List<AnnotationDescription> classAnnotations = new ArrayList<>();
        classAnnotations.add(AnnotationDescription.Builder.ofType(Nested.class).build());
        classAnnotations.add(AnnotationDescription.Builder.ofType(Order.class).define("value", order).build());
        classAnnotations.add(AnnotationDescription.Builder.ofType(DisplayName.class)
                .define("value", member.displayName()).build());
        classAnnotations.add(AnnotationDescription.Builder.ofType(TestMethodOrder.class)
                .define("value", MethodOrderer.OrderAnnotation.class).build());
        classAnnotations.add(AnnotationDescription.Builder.ofType(TestInstance.class)
                .define("value", TestInstance.Lifecycle.PER_METHOD).build());
        if (member.parameterized()) {
            classAnnotations.add(parameterizedClass(method));
        }
        List<AnnotationDescription> leafAnnotations = new ArrayList<>();
        for (Annotation a : method.getDeclaredAnnotations()) {
            Class<? extends Annotation> type = a.annotationType();
            if (ignored(type) || type == DisplayName.class || type == Order.class || type == Test.class
                    || AnnotationSupport.isAnnotated(type, Test.class)
                    || AnnotationSupport.isAnnotated(type, TestTemplate.class)) {
                continue;
            }
            AnnotationDescription copy = qualifiedSource(a, userClass, method);
            // annotations applicable to classes (sources, @Disabled, @Tag, @Timeout, @ExtendWith, conditions ...) go to
            // the nested class and so act on every implementation leaf; method-only ones go to the leaves
            (targets(type, ElementType.TYPE) ? classAnnotations : leafAnnotations).add(copy);
        }

        DynamicType.Builder<Object> builder = new ByteBuddy()
                .subclass(Object.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(name)
                .modifiers(Visibility.PUBLIC)
                .innerTypeOf(carrierType).asMemberType()
                .annotateType(classAnnotations)
                .defineField("this$0", carrierType, Visibility.PACKAGE_PRIVATE, FieldManifestation.FINAL,
                    SyntheticState.SYNTHETIC);
        List<Param> constructorParams = new ArrayList<>();
        constructorParams.add(new Param(carrierType, "this$0", Opcodes.ACC_FINAL | Opcodes.ACC_MANDATED,
            new Annotation[0]));
        net.bytebuddy.implementation.Implementation.Composable constructor = MethodCall
                .invoke(Object.class.getConstructor()).andThen(FieldAccessor.ofField("this$0").setsArgumentAt(0));
        for (int i = 0; i < argumentCount; i++) {
            TypeDefinition type = TypeDefinition.Sort.describe(parameters[i].getParameterizedType());
            builder = builder.defineField("argument" + i, type, Visibility.PRIVATE, FieldManifestation.FINAL);
            constructorParams.add(param(parameters[i]));
            constructor = constructor.andThen(FieldAccessor.ofField("argument" + i).setsArgumentAt(i + 1));
        }
        builder = parameters(builder.defineConstructor(Visibility.PACKAGE_PRIVATE), constructorParams, named)
                .intercept(constructor);

        List<Param> leafParams = new ArrayList<>();
        for (int i = argumentCount; i < parameters.length; i++) {
            leafParams.add(param(parameters[i]));
        }
        int leafOrder = 0;
        for (Implementation impl : spec.implementations()) {
            String leafName = identifier(impl.name());
            for (int n = 2; leafNames.containsKey(leafName); n++) {
                leafName = identifier(impl.name()) + "_" + n;
            }
            leafNames.put(leafName, impl);
            MethodCall call = MethodCall.invoke(method).onField("this$0");
            for (int i = 0; i < parameters.length; i++) {
                call = i < argumentCount ? call.withField("argument" + i) : call.withArgument(i - argumentCount);
            }
            List<AnnotationDescription> annotations = new ArrayList<>(leafAnnotations);
            annotations.add(AnnotationDescription.Builder.ofType(Test.class).build());
            annotations.add(AnnotationDescription.Builder.ofType(Order.class).define("value", ++leafOrder).build());
            annotations.add(AnnotationDescription.Builder.ofType(DisplayName.class).define("value", impl.name()).build());
            annotations.add(AnnotationDescription.Builder.ofType(ImplementationLeaf.class)
                    .define("value", impl.type()).build());
            builder = parameters(builder.defineMethod(leafName, void.class, Visibility.PACKAGE_PRIVATE), leafParams,
                named).intercept(call).annotateMethod(annotations);
        }
        return builder.make();
    }

    private static Param param(Parameter p) {
        return new Param(TypeDefinition.Sort.describe(p.getParameterizedType()), p.isNamePresent() ? p.getName() : null,
            p.getModifiers(), p.getDeclaredAnnotations());
    }

    /** Parameters with names (MethodParameters attribute) only if the user's method was compiled with them. */
    private static MethodDefinition.ExceptionDefinition<Object> parameters(
            MethodDefinition.ParameterDefinition.Initial<Object> definition, List<Param> params, boolean named) {
        if (named) {
            MethodDefinition.ParameterDefinition<Object> d = definition;
            for (Param p : params) {
                d = d.withParameter(p.type(), p.name(), p.modifiers()).annotateParameter(p.annotations());
            }
            return d;
        }
        MethodDefinition.ParameterDefinition.Simple<Object> d = definition;
        for (Param p : params) {
            d = d.withParameter(p.type()).annotateParameter(p.annotations());
        }
        return d;
    }

    /** {@code @ParameterizedTest(...)} → {@code @ParameterizedClass(...)}: the attributes match one to one. */
    private static AnnotationDescription parameterizedClass(Method method) {
        ParameterizedTest test = AnnotationSupport.findAnnotation(method, ParameterizedTest.class).orElseThrow();
        AnnotationDescription.Builder builder = AnnotationDescription.Builder.ofType(ParameterizedClass.class);
        for (Method attribute : ParameterizedClass.class.getDeclaredMethods()) {
            try {
                Object value = ParameterizedTest.class.getMethod(attribute.getName()).invoke(test);
                if (value instanceof String s) {
                    builder = builder.define(attribute.getName(), s);
                }
                else if (value instanceof Boolean b) {
                    builder = builder.define(attribute.getName(), b);
                }
                else if (value instanceof Enum<?> e) {
                    builder = builder.define(attribute.getName(), e);
                }
            }
            catch (ReflectiveOperationException e) {
                // an attribute only @ParameterizedClass has: keep its default
            }
        }
        return builder.build();
    }

    /**
     * {@code @MethodSource("cases")} → {@code @MethodSource("pkg.UserClass#cases")} (same for {@code @FieldSource}):
     * on the nested class a short name would be looked up in the nested class. An empty name means the factory named
     * like the test method.
     */
    private static AnnotationDescription qualifiedSource(Annotation a, Class<?> owner, Method method) {
        if (a instanceof MethodSource s) {
            return AnnotationDescription.Builder.ofType(MethodSource.class)
                    .defineArray("value", qualify(s.value(), owner, method)).build();
        }
        if (a instanceof FieldSource s) {
            return AnnotationDescription.Builder.ofType(FieldSource.class)
                    .defineArray("value", qualify(s.value(), owner, method)).build();
        }
        if (a instanceof MethodSources s) {
            return AnnotationDescription.Builder.ofType(MethodSources.class).defineAnnotationArray("value",
                TypeDescription.ForLoadedType.of(MethodSource.class), Arrays.stream(s.value())
                        .map(m -> qualifiedSource(m, owner, method)).toArray(AnnotationDescription[]::new)).build();
        }
        if (a instanceof FieldSources s) {
            return AnnotationDescription.Builder.ofType(FieldSources.class).defineAnnotationArray("value",
                TypeDescription.ForLoadedType.of(FieldSource.class), Arrays.stream(s.value())
                        .map(f -> qualifiedSource(f, owner, method)).toArray(AnnotationDescription[]::new)).build();
        }
        return AnnotationDescription.ForLoadedAnnotation.of(a);
    }

    private static String[] qualify(String[] names, Class<?> owner, Method method) {
        String[] result = names.length == 0 ? new String[] { "" } : names.clone();
        for (int i = 0; i < result.length; i++) {
            String n = result[i].isBlank() ? method.getName() : result[i].strip();
            result[i] = n.contains("#") ? n : owner.getName() + "#" + n;
        }
        return result;
    }

    private static boolean ignored(Class<? extends Annotation> type) {
        return type.getName().startsWith("java.lang.") || type.getName().startsWith("kotlin.");
    }

    private static boolean targets(Class<? extends Annotation> type, ElementType kind) {
        Target target = type.getAnnotation(Target.class);
        // no @Target: applicable to all declaration contexts
        return target == null || Arrays.asList(target.value()).contains(kind);
    }

    /** Implementation name → Java method name of its leaf. */
    private static String identifier(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
