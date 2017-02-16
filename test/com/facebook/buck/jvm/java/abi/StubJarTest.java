/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;


@RunWith(Parameterized.class)
public class StubJarTest {
  // Test a stub generated by stripping a full jar
  private static final String MODE_JAR_BASED = "JAR_BASED";

  // Test a stub generated from source
  private static final String MODE_SOURCE_BASED = "SOURCE_BASED";

  // Test a stub generated from source, with dependencies missing
  private static final String MODE_SOURCE_BASED_MISSING_DEPS = "SOURCE_BASED_MISSING_DEPS";

  private static final String ANNOTATION_SOURCE = Joiner.on("\n").join(ImmutableList.of(
      "package com.example.buck;",
      "import java.lang.annotation.*;",
      "import static java.lang.annotation.ElementType.*;",
      "@Retention(RetentionPolicy.RUNTIME)",
      "@Target(value={CONSTRUCTOR, FIELD, METHOD, PARAMETER, TYPE})",
      "public @interface Foo {",
      "  int primitiveValue() default 0;",
      "  String[] stringArrayValue() default {\"Hello\"};",
      "  Retention annotationValue() default @Retention(RetentionPolicy.SOURCE);",
      "  Retention[] annotationArrayValue() default {};",
      "  RetentionPolicy enumValue () default RetentionPolicy.CLASS;",
      "}"
  ));

  @Parameterized.Parameter
  public String testingMode;

  @Parameterized.Parameters
  public static Object[] getParameters() {
    return new Object[]{MODE_JAR_BASED, MODE_SOURCE_BASED, MODE_SOURCE_BASED_MISSING_DEPS};
  }

  private static final ImmutableSortedSet<Path> EMPTY_CLASSPATH = ImmutableSortedSet.of();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private ProjectFilesystem filesystem;

  @Before
  public void createTempFilesystem() throws IOException {
    File out = temp.newFolder();
    filesystem = new ProjectFilesystem(out.toPath());
  }

  @Test
  public void emptyClass() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        "package com.example.buck; public class A {}");

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");

    // Verify that the stub jar works by compiling some code that depends on A.
    compileToJar(
        ImmutableSortedSet.of(paths.stubJar),
        Collections.emptyList(),
        "B.java",
        "package com.example.buck; public class B extends A {}",
        temp.newFolder());
  }

  @Test
  public void emptyClassWithAnnotation() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        "package com.example.buck; @Deprecated public class A {}");

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void classWithTwoMethods() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(ImmutableList.of(
            "package com.example.buck;",
            "public class A {",
            "  public String toString() { return null; }",
            "  public void eatCake() {}",
            "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");

  }

  @Test
  public void genericClassSignaturesShouldBePreserved() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T get(String key) { return null; }",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldIgnorePrivateMethods() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  private void privateMethod() {}",
                "  void packageMethod() {}",
                "  protected void protectedMethod() {}",
                "  public void publicMethod() {}",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldPreserveAField() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  protected String protectedField;",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldIgnorePrivateFields() throws IOException {
    notYetImplementedForSource();

    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  private String privateField;",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldPreserveGenericTypesOnFields() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T theField;",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldPreserveGenericTypesOnMethods() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T get(String key) { return null; }",
                "  public <X extends Comparable<T>> X compareWith(T other) { return null; }",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsOnMethods() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  @Foo",
                "  public void cheese(String key) {}",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsOnFields() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  @Foo",
                "  public String name;",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsOnParameters() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public void peynir(@Foo String very, int tasty) {}",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithClassRetention() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on('\n').join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@ClassRetentionAnno()",
            "public class A { }",
            "@Retention(RetentionPolicy.CLASS)",
            "@interface ClassRetentionAnno { }"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithRuntimeRetention() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on('\n').join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@RuntimeRetentionAnno()",
            "public class A { }",
            "@Retention(RetentionPolicy.RUNTIME)",
            "@interface RuntimeRetentionAnno { }"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithPrimitiveValues() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "@Foo(primitiveValue=1)",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithStringArrayValues() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "@Foo(stringArrayValue={\"1\", \"2\"})",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithEnumValues() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@Retention(RetentionPolicy.RUNTIME)",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithEnumArrayValues() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@Target({ElementType.CONSTRUCTOR, ElementType.FIELD})",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithAnnotationValues() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@Foo(annotationValue=@Retention(RetentionPolicy.RUNTIME))",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationsWithAnnotationArrayValues() throws IOException {
    notYetImplementedForMissingClasspath();

    Path annotations = createAnnotationFullJar();
    JarPaths paths = createFullAndStubJars(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "@Foo(annotationArrayValue=@Retention(RetentionPolicy.RUNTIME))",
            "public @interface A {}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void preservesAnnotationPrimitiveDefaultValues() throws IOException {
    JarPaths paths = createAnnotationFullAndStubJars();

    assertClassesStubbedCorrectly(paths, "com/example/buck/Foo.class");
  }

  @Test
  public void preservesAnnotationArrayDefaultValues() throws IOException {
    JarPaths paths = createAnnotationFullAndStubJars();

    assertClassesStubbedCorrectly(paths, "com/example/buck/Foo.class");
  }

  @Test
  public void preservesAnnotationAnnotationDefaultValues() throws IOException {
    JarPaths paths = createAnnotationFullAndStubJars();

    assertClassesStubbedCorrectly(paths, "com/example/buck/Foo.class");
  }

  @Test
  public void preservesAnnotationEnumDefaultValues() throws IOException {
    JarPaths paths = createAnnotationFullAndStubJars();

    assertClassesStubbedCorrectly(paths, "com/example/buck/Foo.class");
  }

  @Test
  public void stubsInnerClasses() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public class B {",
                "    public int count;",
                "    public void foo() {}",
                "  }",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
    assertClassesStubbedCorrectly(paths, "com/example/buck/A$B.class");
  }

  @Test
  public void ignoresAnonymousClasses() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public Runnable r = new Runnable() {",
                "    public void run() { }",
                "  };",
                "}"
            )));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class", "com/example/buck/A$1.class");
  }

  @Test
  public void ignoresLocalClasses() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public void method() {",
                "    class Local { };",
                "  }",
                "}"
            )));

    assertClassesStubbedCorrectly(
        paths,
        "com/example/buck/A.class",
        "com/example/buck/A$1Local.class");
  }

  @Test
  public void abiSafeChangesResultInTheSameOutputJar() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  protected final static int count = 42;",
                "  public String getGreeting() { return \"hello\"; }",
                "  Class<?> clazz;",
                "  public int other;",
                "}"
            )));
    Sha1HashCode originalHash = filesystem.computeSha1(paths.stubJar);

    paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  Class<?> clazz = String.class;",
                "  public String getGreeting() { return \"merhaba\"; }",
                "  protected final static int count = 42;",
                "  public int other = 32;",
                "}"
            )));
    Sha1HashCode secondHash = filesystem.computeSha1(paths.stubJar);

    assertEquals(originalHash, secondHash);
  }

  @Test
  public void shouldIncludeStaticFields() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public static String foo;",
                "  public final static int count = 42;",
                "  protected static void method() {}",
                "}")));
    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void innerClassesInStubsCanBeCompiledAgainst() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "Outer.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class Outer {",
                "  public class Inner {",
                "    public String getGreeting() { return \"hola\"; }",
                "  }",
                "}")));

    compileToJar(
        ImmutableSortedSet.of(paths.stubJar),
        Collections.emptyList(),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck2;",      // Note: different package
                "import com.example.buck.Outer;",  // Inner class becomes available
                "public class A {",
                "  private Outer.Inner field;",    // Reference the inner class
                "}")),
        temp.newFolder());
  }

  @Test
  public void shouldPreserveSynchronizedKeywordOnMethods() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public synchronized void doMagic() {}",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldKeepMultipleFieldsWithSameDescValue() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public static final A SEVERE = new A();",
                "  public static final A NOT_SEVERE = new A();",
                "  public static final A QUITE_MILD = new A();",
                "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldNotStubClinit() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on('\n').join(
            "package com.example.buck;",
            "public class A {",
            "  public static int i = 3;",
            "}"));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void stubJarIsEquallyAtHomeWalkingADirectoryOfClassFiles() throws IOException {
    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public String toString() { return null; }",
                "  public void eatCake() {}",
                "}")));

    Path classDir = temp.newFolder().toPath();
    Unzip.extractZipFile(paths.fullJar, classDir, Unzip.ExistingFileMode.OVERWRITE);

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  @Test
  public void shouldIncludeBridgeMethods() throws IOException {
    notYetImplementedForSource();

    JarPaths paths = createFullAndStubJars(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on('\n').join(ImmutableList.of(
            "package com.example.buck;",
            "public class A implements Comparable<A> {",
            "  public int compareTo(A other) {",
            "    return 0;",
            "  }",
            "}")));

    assertClassesStubbedCorrectly(paths, "com/example/buck/A.class");
  }

  private JarPaths createFullAndStubJars(
      ImmutableSortedSet<Path> classPath,
      String fileName,
      String source) throws IOException {
    File outputDir = temp.newFolder();

    List<Processor> processors = Collections.emptyList();
    StubJarGeneratingProcessor stubJarGenerator = null;
    if (testingMode != MODE_JAR_BASED) {
      stubJarGenerator =
          new StubJarGeneratingProcessor(
              filesystem,
              outputDir.toPath().resolve("sourceStub.jar"),
              SourceVersion.RELEASE_8);
      processors = Collections.singletonList(stubJarGenerator);
    }

    Path fullJar = compileToJar(
        testingMode != MODE_SOURCE_BASED_MISSING_DEPS ? classPath : Collections.emptySortedSet(),
        processors,
        fileName,
        source,
        outputDir);

    Path stubJar;

    if (stubJarGenerator != null) {
      stubJar = stubJarGenerator.getStubJarPath();
    } else {
      stubJar = createStubJar(fullJar);
    }

    return new JarPaths(fullJar, stubJar);
  }

  private Path createStubJar(Path fullJar) throws IOException {
    Path stubJar = fullJar.getParent().resolve("stub.jar");
    new StubJar(fullJar).writeTo(filesystem, stubJar);
    return stubJar;
  }

  private Path compileToJar(
      SortedSet<Path> classpath,
      List<Processor> processors,
      String fileName,
      String source,
      File outputDir) throws IOException {
    File inputs = temp.newFolder();

    File file = new File(inputs, fileName);

    Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(ImmutableSet.of(file));

    List<String> args = Lists.newArrayList("-g", "-d", outputDir.getAbsolutePath());

    if (!classpath.isEmpty()) {
      args.add("-classpath");
      args.add(Joiner.on(File.pathSeparator).join(FluentIterable.from(classpath)
          .transform(filesystem::resolve)));
    }

    JavaCompiler.CompilationTask compilation =
        compiler.getTask(null, fileManager, null, args, null, sourceObjects);

    compilation.setProcessors(processors);

    Boolean result = compilation.call();

    fileManager.close();
    assertNotNull(result);
    assertTrue(result);

    File jar = new File(outputDir, "output.jar");

    try (
        FileOutputStream fos = new FileOutputStream(jar);
        final JarOutputStream os = new JarOutputStream(fos)) {
      SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.getFileName().toString().endsWith(".class")) {
            ZipEntry entry =
                new ZipEntry(MorePaths.pathWithUnixSeparators(outputDir.toPath().relativize(file)));
            os.putNextEntry(entry);
            ByteStreams.copy(Files.newInputStream(file), os);
            os.closeEntry();
          }
          return FileVisitResult.CONTINUE;
        }
      };

      Files.walkFileTree(outputDir.toPath(), visitor);
    }

    return jar.toPath().toAbsolutePath();
  }

  private AbiClass readClass(Path pathToJar, String className) throws IOException {
    return AbiClass.extract(filesystem.getPathForRelativePath(pathToJar), className);
  }

  private JarPaths createAnnotationFullAndStubJars() throws IOException {
    return createFullAndStubJars(
        EMPTY_CLASSPATH,
        "Foo.java",
        ANNOTATION_SOURCE);
  }

  private Path createAnnotationFullJar() throws IOException {
    return compileToJar(
        EMPTY_CLASSPATH,
        Collections.emptyList(),
        "Foo.java",
        ANNOTATION_SOURCE,
        temp.newFolder());
  }

  private void assertClassesStubbedCorrectly(
      JarPaths paths,
      String... classNames) throws IOException {
    for (String className : classNames) {
      AbiClass original = readClass(paths.fullJar, className);
      AbiClass stubbed = readClass(paths.stubJar, className);

      if (isAnonymousOrLocalClass(original.getClassNode())) {
        original = null;
      }

      assertClassStubbedCorrectly(original, stubbed);
    }
  }

  /**
   * A class is stubbed correctly if the stub is exactly the same as its full counterpart, with
   * the following exceptions:
   * <ul>
   *   <li>No private members, &lt;clinit&gt;, synthetic members, bridge methods, or method bodies
   *       are present</li>
   *   <li>Members have been sorted</li>
   * </ul>
   */
  private static void assertClassStubbedCorrectly(AbiClass original, AbiClass stubbed) {
    if (original == null) {
      if (stubbed != null) {
        fail(String.format("Should not have stubbed %s", stubbed.getClassNode().name));
      }
      return;
    }

    ClassNode originalNode = original.getClassNode();
    ClassNode stubbedNode = stubbed.getClassNode();

    assertEquals(originalNode.version, stubbedNode.version);
    assertEquals(originalNode.access, stubbedNode.access);
    assertEquals(originalNode.name, stubbedNode.name);
    assertEquals(originalNode.signature, stubbedNode.signature);
    assertEquals(originalNode.superName, stubbedNode.superName);
    assertThat(stubbedNode.interfaces, Matchers.equalTo(originalNode.interfaces));
    assertNull(stubbedNode.sourceFile);
    assertNull(stubbedNode.sourceDebug);
    assertEquals(originalNode.outerClass, stubbedNode.outerClass);
    assertNull(stubbedNode.outerMethod);
    assertNull(stubbedNode.outerMethodDesc);
    assertAnnotationsEqual(originalNode.visibleAnnotations, stubbedNode.visibleAnnotations);
    assertAnnotationsEqual(originalNode.invisibleAnnotations, stubbedNode.invisibleAnnotations);
    assertTypeAnnotationsEqual(
        originalNode.visibleTypeAnnotations,
        stubbedNode.visibleTypeAnnotations);
    assertTypeAnnotationsEqual(
        originalNode.invisibleTypeAnnotations,
        stubbedNode.invisibleTypeAnnotations);
    assertEquals(originalNode.attrs, stubbedNode.attrs);
    assertInnerClassesStubbedCorrectly(originalNode.innerClasses, stubbedNode.innerClasses);
    assertFieldsStubbedCorrectly(originalNode.fields, stubbedNode.fields);
    assertMethodsStubbedCorrectly(originalNode.methods, stubbedNode.methods);
  }

  private static void assertInnerClassesStubbedCorrectly(
      List<InnerClassNode> original,
      List<InnerClassNode> stubbed) {
    List<InnerClassNode> filteredOriginal = original.stream()
        .filter(node -> !isAnonymousOrLocalClass(node))
        .collect(Collectors.toList());

    assertMembersStubbedCorrectly(
        filteredOriginal,
        stubbed,
        node -> node.name,
        node -> node.access,
        StubJarTest::assertInnerClassStubbedCorrectly);
  }

  private static boolean isAnonymousOrLocalClass(ClassNode node) {
    return isLocalClass(node) || isAnonymousClass(node);
  }

  private static boolean isLocalClass(ClassNode node) {
    return node.outerMethod != null;
  }

  private static boolean isAnonymousClass(ClassNode node) {
    if (node.outerClass == null) {
      return false;
    }

    for (InnerClassNode innerClass : node.innerClasses) {
      if (innerClass.name.equals(node.name) && innerClass.innerName == null) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAnonymousOrLocalClass(InnerClassNode node) {
    return node.innerName == null || node.outerName == null;
  }

  private static void assertMethodsStubbedCorrectly(
      List<MethodNode> original,
      List<MethodNode> stubbed) {
    assertMembersStubbedCorrectly(
        original,
        stubbed,
        node -> String.format("%s: %s", node.name, node.desc),
        node -> node.access,
        StubJarTest::assertMethodStubbedCorrectly);
  }

  private static void assertFieldsStubbedCorrectly(
      List<FieldNode> original,
      List<FieldNode> stubbed) {
    assertMembersStubbedCorrectly(
        original,
        stubbed,
        node -> node.name,
        node -> node.access,
        StubJarTest::assertFieldStubbedCorrectly);
  }

  private static <M> void assertMembersStubbedCorrectly(
      List<M> original,
      List<M> stubbed,
      Function<M, String> getSortKey,
      Function<M, Integer> getAccess,
      BiFunction<M, M, Void> assertMemberStubbedCorrectly) {
    Map<String, M> originalMembers = original.stream()
        .collect(Collectors.toMap(getSortKey, Function.identity()));

    // Never stub clinit
    originalMembers.remove("<clinit>: ()V");

    Iterator<M> originalMemberIterator = originalMembers.values().iterator();
    while (originalMemberIterator.hasNext()) {
      M member = originalMemberIterator.next();
      int access = getAccess.apply(member);
      // Never stub things that are private
      if ((access & (Opcodes.ACC_PRIVATE)) != 0) {
        originalMemberIterator.remove();
      }
    }

    for (M stubbedMember : stubbed) {
      String sortKey = getSortKey.apply(stubbedMember);
      M originalMember = originalMembers.remove(sortKey);
      if (originalMember == null) {
        fail(String.format(
            "Should not have stubbed %s %s",
            stubbedMember.getClass().getSimpleName(),
            getSortKey.apply(stubbedMember)));
      }

      assertMemberStubbedCorrectly.apply(originalMember, stubbedMember);
    }

    for (M originalMember : originalMembers.values()) {
      fail(String.format(
          "Failed to stub %s %s",
          originalMember.getClass().getSimpleName(),
          getSortKey.apply(originalMember)));
    }

    // Output stubs in sorted order
    List<String> actualOrder = stubbed.stream()
        .map(getSortKey)
        .collect(Collectors.toList());

    List<String> expectedOrder = new ArrayList<>(actualOrder);
    Collections.sort(expectedOrder);
    assertThat(actualOrder, Matchers.equalTo(expectedOrder));
  }

  private static Void assertInnerClassStubbedCorrectly(
      InnerClassNode original,
      InnerClassNode stubbed) {
    assertEquals(original.name, stubbed.name);
    assertEquals(original.outerName, stubbed.outerName);
    assertEquals(original.innerName, stubbed.innerName);
    assertEquals(original.access, stubbed.access);
    return null;
  }

  private static Void assertMethodStubbedCorrectly(MethodNode original, MethodNode stubbed) {
    assertEquals(original.access, stubbed.access);
    assertEquals(original.name, stubbed.name);
    assertEquals(original.desc, stubbed.desc);
    assertEquals(original.signature, stubbed.signature);
    assertEquals(original.exceptions, stubbed.exceptions);
    assertParametersEqual(original.parameters, stubbed.parameters);
    assertAnnotationsEqual(original.visibleAnnotations, stubbed.visibleAnnotations);
    assertAnnotationsEqual(original.invisibleAnnotations, stubbed.invisibleAnnotations);
    assertTypeAnnotationsEqual(original.visibleTypeAnnotations, stubbed.visibleTypeAnnotations);
    assertTypeAnnotationsEqual(original.invisibleTypeAnnotations, stubbed.invisibleTypeAnnotations);
    assertAnnotationValueEquals(original.annotationDefault, stubbed.annotationDefault);
    assertParameterAnnotationsEqual(
        original.visibleParameterAnnotations,
        stubbed.visibleParameterAnnotations);
    assertParameterAnnotationsEqual(
        original.invisibleParameterAnnotations,
        stubbed.invisibleParameterAnnotations);

    return null;
  }

  private static Void assertFieldStubbedCorrectly(FieldNode original, FieldNode stubbed) {
    assertEquals(original.access, stubbed.access);
    assertEquals(original.name, stubbed.name);
    assertEquals(original.desc, stubbed.desc);
    assertEquals(original.signature, stubbed.signature);
    assertEquals(original.value, stubbed.value);
    assertAnnotationsEqual(original.visibleAnnotations, stubbed.visibleAnnotations);
    assertAnnotationsEqual(original.invisibleAnnotations, stubbed.invisibleAnnotations);
    assertTypeAnnotationsEqual(original.visibleTypeAnnotations, stubbed.visibleTypeAnnotations);
    assertEquals(original.attrs, stubbed.attrs);

    return null;
  }

  private static void assertParameterAnnotationsEqual(
      List<AnnotationNode>[] expected,
      List<AnnotationNode>[] seen) {
    if (expected == null) {
      assertNull(seen);
      return;
    }

    assertSame(expected.length, seen.length);

    for (int i = 0; i < expected.length; i++) {
      assertAnnotationsEqual(expected[i], seen[i]);
    }
  }


  private static void assertAnnotationsEqual(
      List<AnnotationNode> expected,
      List<AnnotationNode> seen) {
    assertListsEqual(expected, seen, StubJarTest::annotationToString);
  }

  private static void assertParametersEqual(
      List<ParameterNode> expected,
      List<ParameterNode> seen) {
    assertListsEqual(expected, seen, StubJarTest::parameterToString);
  }

  private static void assertTypeAnnotationsEqual(
      List<TypeAnnotationNode> expected,
      List<TypeAnnotationNode> seen) {
    assertListsEqual(expected, seen, StubJarTest::typeAnnotationToString);
  }

  private static <T> void assertListsEqual(
      List<T> expected,
      List<T> seen,
      Function<T, String> toString) {
    if (expected == null) {
      assertNull(seen);
      return;
    }
    assertNotNull(String.format(
        "Stubbed no %s's",
        expected.get(0).getClass().getSimpleName()),
        seen);

    assertEquals(
        expected.stream()
            .map(toString)
            .collect(Collectors.toList()),
        seen.stream()
            .map(toString)
            .collect(Collectors.toList()));
  }

  private static String typeAnnotationToString(TypeAnnotationNode typeAnnotationNode) {
    return String.format(
        "%d %s %s(%s)",
        typeAnnotationNode.typeRef,
        typeAnnotationNode.typePath,
        typeAnnotationNode.desc,
        annotationValuesToString(typeAnnotationNode.values));
  }

  private static String parameterToString(ParameterNode parameter) {
    return String.format("0x%x %s", parameter.access, parameter.name);
  }

  private static String annotationToString(AnnotationNode annotationNode) {
    return String.format(
        "%s(%s)",
        annotationNode.desc,
        annotationValuesToString(annotationNode.values));
  }

  private static void assertAnnotationValueEquals(Object expected, Object actual) {
    assertEquals(annotationValueToString(expected), annotationValueToString(actual));
  }

  private static String annotationValueToString(Object value) {
    if (value instanceof AnnotationNode) {
      return annotationToString((AnnotationNode) value);
    } else if (value instanceof List) {
      return annotationValuesToString((List<?>) value);
    } else if (value instanceof String[]) {
      String[] valueArray = (String[]) value;
      return String.format("%s.%s", valueArray[0], valueArray[1]);
    }

    return String.valueOf(value);
  }

  private static String annotationValuesToString(List<?> values) {
    if (values == null) {
      return "null";
    }

    StringBuilder resultBuilder = new StringBuilder();

    resultBuilder.append('[');
    for (int i = 0; i < values.size(); i++) {
      resultBuilder.append(annotationValueToString(values.get(i)));

      if (i > 0) {
        resultBuilder.append(", ");
      }
    }
    resultBuilder.append(']');
    return resultBuilder.toString();
  }

  private void notYetImplementedForMissingClasspath() {
    assumeThat(testingMode, Matchers.not(Matchers.equalTo(MODE_SOURCE_BASED_MISSING_DEPS)));
  }

  private void notYetImplementedForSource() {
    assumeThat(testingMode, Matchers.equalTo(MODE_JAR_BASED));
  }

  private static class JarPaths {
    public final Path fullJar;
    public final Path stubJar;

    public JarPaths(Path fullJar, Path stubJar) {
      this.fullJar = fullJar;
      this.stubJar = stubJar;
    }
  }
}
