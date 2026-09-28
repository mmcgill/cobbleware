package io.github.mmcgill.cobbleware.tools;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Produces a source-level, one-hop call graph around one Java class. */
public final class CallGraphBuilder {
    private CallGraphBuilder() {}

    /**
     * Arguments: project directory, source roots, compilation classpath, output DOT path, fully qualified class name,
     * include external callees.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Expected project directory, source roots, classpath, output path, class name, and external-callee flag");
        }

        Path projectDirectory = Path.of(args[0]);
        List<Path> sourceRoots = paths(args[1]);
        List<Path> classpath = paths(args[2]);
        Path output = Path.of(args[3]);
        String targetClassName = args[4];
        boolean includeExternalCallees = Boolean.parseBoolean(args[5]);
        List<Path> sourceFiles = sourceFiles(sourceRoots);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No Java source files found below " + sourceRoots);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required to build a call graph");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of("-proc:none", "--release", "25");
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, units);
            Trees trees = Trees.instance(task);
            Iterable<? extends CompilationUnitTree> parsedUnits = task.parse();
            task.analyze();
            failForErrors(diagnostics, projectDirectory);

            TypeElement targetType = task.getElements().getTypeElement(targetClassName);
            if (targetType == null) {
                throw new IllegalArgumentException("Could not resolve target class " + targetClassName);
            }
            CallGraph graph = new CallGraph(targetType);
            graph.addTargetExecutables();
            for (CompilationUnitTree unit : parsedUnits) {
                new InvocationScanner(trees, targetType, graph, includeExternalCallees).scan(unit, null);
            }
            graph.writeDot(output);
        }
    }

    private static List<Path> paths(String pathList) {
        if (pathList.isEmpty()) {
            return List.of();
        }
        return Stream.of(pathList.split(java.util.regex.Pattern.quote(System.getProperty("path.separator"))))
                .filter(entry -> !entry.isEmpty())
                .map(Path::of)
                .toList();
    }

    private static List<Path> sourceFiles(List<Path> sourceRoots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static void failForErrors(DiagnosticCollector<JavaFileObject> diagnostics, Path projectDirectory) {
        List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> projectDirectory.relativize(
                                Path.of(diagnostic.getSource().toUri())) + ":" + diagnostic.getLineNumber() + ": "
                        + diagnostic.getMessage(null))
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Could not analyze source:\n" + String.join("\n", errors));
        }
    }

    private static final class InvocationScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final TypeElement targetType;
        private final CallGraph graph;
        private final boolean includeExternalCallees;
        private final Deque<ExecutableElement> enclosingExecutables = new ArrayDeque<>();

        private InvocationScanner(
                Trees trees, TypeElement targetType, CallGraph graph, boolean includeExternalCallees) {
            this.trees = trees;
            this.targetType = targetType;
            this.graph = graph;
            this.includeExternalCallees = includeExternalCallees;
        }

        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executable) {
                enclosingExecutables.push(executable);
                try {
                    return super.visitMethod(method, unused);
                } finally {
                    enclosingExecutables.pop();
                }
            }
            return super.visitMethod(method, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
            recordInvocation(trees.getElement(getCurrentPath()), "calls");
            return super.visitMethodInvocation(invocation, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
            recordInvocation(trees.getElement(getCurrentPath()), "references");
            return super.visitMemberReference(reference, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree newClass, Void unused) {
            recordInvocation(trees.getElement(getCurrentPath()), "constructs");
            return super.visitNewClass(newClass, unused);
        }

        private void recordInvocation(Element invoked, String kind) {
            if (!(invoked instanceof ExecutableElement callee)) {
                return;
            }
            if (isJdkClass(callee)) {
                return;
            }
            Element caller = enclosingExecutables.peek();
            if (caller == null) {
                caller = initializerOwner(getCurrentPath());
            }
            if (caller == null) {
                return;
            }
            if (isDeclaredByTarget(caller)) {
                if (includeExternalCallees || isDeclaredByTarget(callee)) {
                    graph.addEdge(caller, callee, kind);
                }
            } else if (graph.isPublicEntryPoint(callee)) {
                graph.addEdge(caller, callee, kind);
            }
        }

        private Element initializerOwner(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) {
                if (current.getLeaf() instanceof ClassTree) {
                    Element element = trees.getElement(current);
                    if (element instanceof TypeElement type) {
                        return new InitializerElement(type);
                    }
                }
            }
            return null;
        }

        private boolean isDeclaredByTarget(Element element) {
            if (element instanceof InitializerElement initializer) {
                return initializer.owner.equals(targetType);
            }
            return element instanceof ExecutableElement executable
                    && executable.getEnclosingElement().equals(targetType);
        }

        private boolean isDeclaredByTarget(ExecutableElement executable) {
            return executable.getEnclosingElement().equals(targetType);
        }

        private static boolean isJdkClass(ExecutableElement executable) {
            if (!(executable.getEnclosingElement() instanceof TypeElement type)) {
                return false;
            }
            String name = type.getQualifiedName().toString();
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")
                    || name.startsWith("com.sun.");
        }
    }

    private static final class CallGraph {
        private final TypeElement targetType;
        private final Set<ExecutableElement> publicEntryPoints = new LinkedHashSet<>();
        private final Map<String, String> nodeIds = new LinkedHashMap<>();
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Set<Edge> edges = new TreeSet<>();

        private CallGraph(TypeElement targetType) {
            this.targetType = targetType;
        }

        private void addTargetExecutables() {
            for (Element member : targetType.getEnclosedElements()) {
                if (member instanceof ExecutableElement executable
                        && (member.getKind() == ElementKind.METHOD || member.getKind() == ElementKind.CONSTRUCTOR)) {
                    addNode(executable);
                    if (executable.getModifiers().contains(Modifier.PUBLIC)) {
                        publicEntryPoints.add(executable);
                    }
                }
            }
        }

        private boolean isPublicEntryPoint(ExecutableElement executable) {
            return executable.getEnclosingElement().equals(targetType) && publicEntryPoints.contains(executable);
        }

        private void addEdge(Element caller, ExecutableElement callee, String kind) {
            String callerId = addNode(caller);
            String calleeId = addNode(callee);
            edges.add(new Edge(callerId, calleeId, kind));
        }

        private String addNode(Element element) {
            String key = elementKey(element);
            String existing = nodeIds.get(key);
            if (existing != null) {
                return existing;
            }
            String id = "n" + nodeIds.size();
            nodeIds.put(key, id);
            nodes.put(id, Node.from(element, targetType));
            return id;
        }

        private void writeDot(Path output) throws IOException {
            Files.createDirectories(output.getParent());
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
                writer.println("digraph CallGraph {");
                writer.println("  graph [");
                writer.println("    label=\"One-hop call graph: "
                        + dot(targetType.getQualifiedName().toString()) + "\",");
                writer.println("    labelloc=t,");
                writer.println("    rankdir=LR,");
                writer.println("    nodesep=0.35,");
                writer.println("    ranksep=1.1,");
                writer.println("    ordering=out");
                writer.println("  ];");
                writer.println("  node [shape=box, fontname=\"Helvetica\"];");
                writer.println("  edge [color=\"#94A3B8\", penwidth=1, arrowsize=0.65];");
                for (Map.Entry<String, Node> node : nodes.entrySet()) {
                    Node details = node.getValue();
                    writer.println("  " + node.getKey() + " [label=\"" + dot(details.label)
                            + "\", style=filled, fillcolor=\"" + details.fillColor + "\", color=\"#111827\", penwidth="
                            + details.penWidth + "]; ");
                }
                for (Edge edge : edges) {
                    writer.println("  " + edge.callerId + " -> " + edge.calleeId + edgeAttributes(edge) + ";");
                }
                writer.println("}");
            }
        }
    }

    private static String edgeAttributes(Edge edge) {
        return switch (edge.kind) {
            case "calls" -> "";
            case "references" -> " [style=dashed, xlabel=\"references\", fontsize=8]";
            case "constructs" -> " [color=\"#7C3AED\", penwidth=2]";
            default -> throw new IllegalArgumentException("Unknown call-graph edge kind " + edge.kind);
        };
    }

    private record Node(String label, String fillColor, int penWidth) {
        private static Node from(Element element, TypeElement targetType) {
            boolean belongsToTarget = element instanceof InitializerElement initializer
                    ? initializer.owner.equals(targetType)
                    : element instanceof ExecutableElement executable
                            && executable.getEnclosingElement().equals(targetType);
            if (!(element instanceof ExecutableElement executable)) {
                return new Node(elementLabel(element, targetType), "#E5E7EB", belongsToTarget ? 2 : 1);
            }
            Set<Modifier> modifiers = executable.getModifiers();
            String fillColor = modifiers.contains(Modifier.PUBLIC)
                    ? "#BBF7D0"
                    : modifiers.contains(Modifier.PROTECTED)
                            ? "#BFDBFE"
                            : modifiers.contains(Modifier.PRIVATE) ? "#FECACA" : "#FDE68A";
            return new Node(elementLabel(element, targetType), fillColor, belongsToTarget ? 2 : 1);
        }
    }

    private static String elementKey(Element element) {
        if (element instanceof InitializerElement initializer) {
            return initializer.owner.getQualifiedName() + "#<initializer>";
        }
        if (element instanceof ExecutableElement executable) {
            String owner = executable.getEnclosingElement() instanceof TypeElement type
                    ? type.getQualifiedName().toString()
                    : executable.getEnclosingElement().toString();
            return owner + "#" + executable;
        }
        return element.toString();
    }

    private static String elementLabel(Element element, TypeElement targetType) {
        if (element instanceof InitializerElement initializer) {
            return ownerName(initializer.owner, targetType) + "#<initializer>";
        }
        if (!(element instanceof ExecutableElement executable)) {
            return element.toString();
        }
        String owner = executable.getEnclosingElement() instanceof TypeElement type
                ? ownerName(type, targetType)
                : executable.getEnclosingElement().toString();
        String name = executable.getKind() == ElementKind.CONSTRUCTOR
                ? "<init>"
                : executable.getSimpleName().toString();
        return owner + "#" + name + "()";
    }

    private static String ownerName(TypeElement owner, TypeElement targetType) {
        String ownerName = owner.getQualifiedName().toString();
        String targetPackage = packageName(targetType);
        if (!packageName(owner).equals(targetPackage)) {
            return ownerName;
        }
        return targetPackage.isEmpty() ? ownerName : ownerName.substring(targetPackage.length() + 1);
    }

    private static String packageName(TypeElement type) {
        Element element = type;
        while (element.getEnclosingElement() instanceof TypeElement) {
            element = element.getEnclosingElement();
        }
        return element.getEnclosingElement().toString();
    }

    private static String dot(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Edge(String callerId, String calleeId, String kind) implements Comparable<Edge> {
        @Override
        public int compareTo(Edge other) {
            return Comparator.comparing(Edge::callerId)
                    .thenComparing(Edge::calleeId)
                    .thenComparing(Edge::kind)
                    .compare(this, other);
        }
    }

    /** A synthetic element representing code in a class or instance initializer. */
    private static final class InitializerElement implements Element {
        private final TypeElement owner;

        private InitializerElement(TypeElement owner) {
            this.owner = owner;
        }

        @Override
        public TypeMirror asType() {
            return owner.asType();
        }

        @Override
        public ElementKind getKind() {
            return ElementKind.STATIC_INIT;
        }

        @Override
        public Set<Modifier> getModifiers() {
            return Set.of();
        }

        @Override
        public javax.lang.model.element.Name getSimpleName() {
            return owner.getSimpleName();
        }

        @Override
        public Element getEnclosingElement() {
            return owner;
        }

        @Override
        public List<? extends Element> getEnclosedElements() {
            return List.of();
        }

        @Override
        public <A extends java.lang.annotation.Annotation> A getAnnotation(Class<A> annotationType) {
            return null;
        }

        @Override
        public <A extends java.lang.annotation.Annotation> A[] getAnnotationsByType(Class<A> annotationType) {
            return owner.getAnnotationsByType(annotationType);
        }

        @Override
        public List<javax.lang.model.element.AnnotationMirror> getAnnotationMirrors() {
            return List.of();
        }

        @Override
        public <R, P> R accept(javax.lang.model.element.ElementVisitor<R, P> visitor, P parameter) {
            return visitor.visitUnknown(this, parameter);
        }
    }
}
