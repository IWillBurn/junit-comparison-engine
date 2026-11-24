package org.atpfivt.comparison.engine;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeDescription.Generic;
import net.bytebuddy.description.type.TypeDescription.Generic.Visitor.Substitutor.WithoutTypeSubstitution;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Builder.MethodDefinition;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.Implementation.Composable;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.TestMethod;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Generates a subclass of the user's test class in its package and loader. For example, a parameterized
 * {@code MulTest.mul(int, int, Calc)} becomes:
 * <pre>{@code
 * class MulTest$$Comparison$1 extends MulTest {
 *     @Nested @ParameterizedClass @CsvSource({"2, 3", "4, 5"})
 *     class mul {
 *         mul(int a, int b) { ... } // hold one argument set
 *         @Test @ImplementationLeaf(value = "GOOD", parameterIndex = 0, variantType = CalcVariant.class)
 *         void GOOD(Calc calc) { MulTest$$Comparison$1.this.mul(a, b, calc); }
 *     }
 * }
 * }</pre>
 * The nested Launcher selects the generated member classes explicitly, leaving inherited tests alone.
 */
final class CarrierGenerator {

    /** Java 17 class files, whatever JDK runs the tests: a new JDK does not need a new Byte Buddy. */
    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy(ClassFileVersion.JAVA_V17);

    /** The enclosing instance of a generated inner class, in a field named the way javac names it. */
    private static final String OUTER_INSTANCE = "this$0";

    /** {@code Object()}: every generated constructor calls it first. */
    private static final MethodDescription.InDefinedShape OBJECT_CONSTRUCTOR = TypeDescription.ForLoadedType
            .of(Object.class).getDeclaredMethods().filter(ElementMatchers.isConstructor()).getOnly();

    private static final MethodDescription.InDefinedShape PREDICATE_TEST = TypeDescription.ForLoadedType
            .of(Predicate.class).getDeclaredMethods().filter(ElementMatchers.named("test")).getOnly();

    private static final AtomicInteger CARRIER_NUMBER = new AtomicInteger();

    private CarrierGenerator() {
    }

    static Carrier generate(ComparisonClass model, List<CarrierPlan> plans) throws IllegalAccessException {
        Class<?> userClass = model.testClass();
        String carrierName = userClass.getName() + "$$Comparison$" + CARRIER_NUMBER.incrementAndGet();
        DynamicType.Builder<?> carrier = BYTE_BUDDY
                .subclass(userClass, ConstructorStrategy.Default.IMITATE_SUPER_CLASS.withInheritedAnnotations())
                .name(carrierName)
                .annotateType(CarrierAnnotations.carrier(userClass, model.displayName()));
        if (model.implementations().getFirst().implementationType() != null) {
            carrier = carrier.annotateType(AnnotationDescription.Builder.ofType(ExtendWith.class)
                    .defineTypeArray("value", ImplementationResolver.class).build());
        }
        else {
            var declaration = AnnotationSupport.findAnnotation(userClass, ComparisonTest.class).orElseThrow();
            var source = ReflectionSupport.newInstance(declaration.source());
            carrier = carrier.defineField("$comparisonSourceExtension", Extension.class,
                        Visibility.PACKAGE_PRIVATE, Ownership.STATIC)
                    .annotateField(AnnotationDescription.Builder.ofType(RegisterExtension.class).build())
                    .initializer(new LoadedTypeInitializer.ForStaticField("$comparisonSourceExtension",
                        new ComparisonSourceExtension<>(source, model.implementations())));
        }
        // The nested classes need the carrier's type before we can add them to it.
        TypeDescription carrierType = carrier.toTypeDescription();

        UniqueNames simpleNames = new UniqueNames();
        List<UnloadedNestedClass> nestedClasses = new ArrayList<>();
        for (CarrierPlan nestedClassPlan : plans) {
            String name = carrierName + "$" + simpleNames.next(nestedClassPlan.method().method().getName());
            nestedClasses.add(new NestedClassWriter(carrierType, userClass, nestedClassPlan)
                    .write(name, nestedClasses.size() + 1));
        }

        var nestedTypes = nestedClasses.stream().map(UnloadedNestedClass::type).toList();
        try (var unloaded = carrier.declaredTypes(nestedTypes.stream().map(DynamicType::getTypeDescription).toList())
                .make().include(nestedTypes)) {
            Map<TypeDescription, Class<?>> loaded = unloaded
                    .load(userClass.getClassLoader(), ClassLoadingStrategy.UsingLookup.of(
                        MethodHandles.privateLookupIn(userClass, MethodHandles.lookup())))
                    .getAllLoaded();
            return new Carrier(loaded.get(carrierType),
                nestedClasses.stream().map(nested -> nested.load(loaded)).toList());
        }
    }

    /** A generated nested class before loading. */
    private record UnloadedNestedClass(DynamicType.Unloaded<Object> type, CarrierPlan plan,
            Map<String, Implementation> leaves) {

        Carrier.NestedClass load(Map<TypeDescription, Class<?>> loaded) {
            return new Carrier.NestedClass(loaded.get(type.getTypeDescription()), plan, leaves);
        }
    }

    // Type variables belong to the user method or class. Generated members can only use their erased bounds.
    private static final Generic.Visitor<Generic> ERASE_TYPE_VARIABLES = new WithoutTypeSubstitution() {
        @Override
        public Generic onTypeVariable(Generic variable) {
            return variable.asErasure().asGenericType();
        }
    };

    private record ParameterShape(TypeDefinition type, String name, int modifiers,
            List<Annotation> annotations) {

        static ParameterShape of(Parameter parameter) {
            Generic type = TypeDefinition.Sort.describe(parameter.getParameterizedType()).accept(ERASE_TYPE_VARIABLES);
            return new ParameterShape(type, parameter.getName(), parameter.getModifiers(),
                List.of(parameter.getDeclaredAnnotations()));
        }
    }

    /** Writes the {@code @Nested} class of one user method (see the class comment). */
    private static final class NestedClassWriter {

        private final TypeDescription carrierType;
        private final CarrierPlan plan;
        private final Method method;
        private final List<ParameterShape> arguments;
        private final String[] argumentFields;
        /** Parameter names (the MethodParameters attribute) only if the user's method was compiled with them. */
        private final boolean namedParameters;
        private final List<ParameterShape> leafParameters;
        private final CarrierAnnotations annotations;

        NestedClassWriter(TypeDescription carrierType, Class<?> userClass, CarrierPlan plan) {
            this.carrierType = carrierType;
            this.plan = plan;
            TestMethod test = plan.method();
            this.method = test.method();
            Parameter[] declaredParameters = method.getParameters();
            List<ParameterShape> parameters = Arrays.stream(declaredParameters).map(ParameterShape::of).toList();
            int argumentCount = test.argumentCount();
            this.arguments = parameters.subList(0, argumentCount);
            this.argumentFields = IntStream.range(0, argumentCount).mapToObj(i -> "argument" + i).toArray(String[]::new);
            this.namedParameters = declaredParameters.length > 0 && declaredParameters[0].isNamePresent();
            this.leafParameters = parameters.subList(argumentCount, parameters.size());
            this.annotations = new CarrierAnnotations(userClass, method, test.displayName(), test.parameterized());
        }

        UnloadedNestedClass write(String name, int order) {
            DynamicType.Builder<Object> builder = BYTE_BUDDY
                    .subclass(Object.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .name(name)
                    .modifiers(Visibility.PUBLIC)
                    .innerTypeOf(carrierType).asMemberType()
                    .annotateType(annotations.nestedClass(order))
                    .defineField(OUTER_INSTANCE, carrierType, Visibility.PACKAGE_PRIVATE, FieldManifestation.FINAL,
                        SyntheticState.SYNTHETIC);
            builder = defineConstructor(builder);
            builder = addSourceResolver(builder);
            UniqueNames leafNames = new UniqueNames();
            for (CarrierAnnotations.InstanceCondition condition : annotations.instanceConditions()) {
                leafNames.next(condition.name());
                builder = builder.defineMethod(condition.name(), boolean.class, Visibility.PRIVATE, Ownership.STATIC)
                        .withParameters(ExtensionContext.class)
                        .intercept(MethodCall.invoke(PREDICATE_TEST).on(condition.predicate(), Predicate.class)
                                .withAllArguments());
            }
            Map<String, Implementation> leaves = new LinkedHashMap<>();
            for (Implementation implementation : plan.implementations()) {
                String leafName = leafNames.next(javaIdentifier(implementation.name()));
                leaves.put(leafName, implementation);
                builder = defineLeaf(builder, leafName, leaves.size(), implementation);
            }
            return new UnloadedNestedClass(builder.make(), plan, leaves);
        }

        private DynamicType.Builder<Object> addSourceResolver(DynamicType.Builder<Object> builder) {
            return annotations.sourceResolver().map(resolver -> builder
                    .defineField("$comparisonSourceResolver", ParameterResolver.class, Visibility.PACKAGE_PRIVATE,
                        Ownership.STATIC)
                    .annotateField(AnnotationDescription.Builder.ofType(RegisterExtension.class).build())
                    .initializer(new LoadedTypeInitializer.ForStaticField("$comparisonSourceResolver", resolver)))
                    .orElse(builder);
        }

        /** {@code mul(MulTest$$Comparison$1 this$0, int a, int b, int expected)}: keeps them in fields. */
        private DynamicType.Builder<Object> defineConstructor(DynamicType.Builder<Object> builder) {
            List<ParameterShape> shapes = new ArrayList<>();
            shapes.add(new ParameterShape(carrierType, OUTER_INSTANCE, Opcodes.ACC_FINAL | Opcodes.ACC_MANDATED,
                List.of()));
            Composable body = MethodCall.invoke(OBJECT_CONSTRUCTOR)
                    .andThen(FieldAccessor.ofField(OUTER_INSTANCE).setsArgumentAt(0));
            for (int i = 0; i < arguments.size(); i++) {
                ParameterShape argument = arguments.get(i);
                String field = argumentFields[i];
                builder = builder.defineField(field, argument.type(), Visibility.PRIVATE, FieldManifestation.FINAL);
                shapes.add(argument);
                body = body.andThen(FieldAccessor.ofField(field).setsArgumentAt(i + 1));
            }
            return withParameters(builder.defineConstructor(Visibility.PACKAGE_PRIVATE), shapes).intercept(body);
        }

        /** {@code @Test void Good(Calc calc) { this$0.mul(argument0, argument1, argument2, calc); }} */
        private DynamicType.Builder<Object> defineLeaf(DynamicType.Builder<Object> builder, String name, int order,
                Implementation implementation) {
            MethodCall call = MethodCall.invoke(method).onField(OUTER_INSTANCE)
                    .withField(argumentFields).withAllArguments();
            return withParameters(builder.defineMethod(name, void.class, Visibility.PACKAGE_PRIVATE), leafParameters)
                    .intercept(call)
                    .annotateMethod(annotations.leaf(order, implementation,
                        plan.method().implementationIndex() - arguments.size()));
        }

        private MethodDefinition.ExceptionDefinition<Object> withParameters(
                MethodDefinition.ParameterDefinition.Initial<Object> definition, List<ParameterShape> shapes) {
            if (namedParameters) {
                MethodDefinition.ParameterDefinition<Object> named = definition;
                for (ParameterShape shape : shapes) {
                    named = named.withParameter(shape.type(), shape.name(), shape.modifiers())
                            .annotateParameter(shape.annotations());
                }
                return named;
            }
            MethodDefinition.ParameterDefinition.Simple<Object> unnamed = definition;
            for (ParameterShape shape : shapes) {
                unnamed = unnamed.withParameter(shape.type()).annotateParameter(shape.annotations());
            }
            return unnamed;
        }
    }

    /** An implementation name as a Java method name: {@code Good#2} → {@code Good_2}. */
    private static String javaIdentifier(String name) {
        StringBuilder identifier = new StringBuilder();
        name.codePoints().forEach(c -> identifier.appendCodePoint(Character.isJavaIdentifierPart(c) ? c : '_'));
        if (identifier.isEmpty() || !Character.isJavaIdentifierStart(identifier.codePointAt(0))) {
            identifier.insert(0, '_');
        }
        return identifier.toString();
    }
}
