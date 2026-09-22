package org.eclipse.mieux.mcp.server.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.Tool;
import org.eclipse.mieux.mcp.server.registry.ToolExecutionException;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;

/** Safe workspace/resource operations for creating and validating MCP-driven projects. */
final class WorkspaceTools {

	private WorkspaceTools() {
	}

	static void registerAll(ToolRegistry registry) {
		registry.register(new ListProjectsTool());
		registry.register(new CreateJavaProjectTool());
		registry.register(new ListFilesTool());
		registry.register(new ReadFileTool());
		registry.register(new WriteFileTool());
		registry.register(new BuildProjectTool());
		registry.register(new RunJavaMainTool());
	}

	private abstract static class WorkspaceTool implements Tool {
		@Override
		public final Object execute(Map<String, Object> arguments) throws ToolExecutionException {
			try {
				return executeWorkspace(arguments);
			} catch (ToolExecutionException e) {
				throw e;
			} catch (CoreException | IOException e) {
				throw new ToolExecutionException("Workspace operation failed: " + e.getMessage());
			}
		}

		abstract Object executeWorkspace(Map<String, Object> arguments)
				throws CoreException, IOException, ToolExecutionException;

		static String required(Map<String, Object> arguments, String key) throws ToolExecutionException {
			Object value = arguments.get(key);
			if (!(value instanceof String text) || text.isBlank()) {
				throw new ToolExecutionException("Missing required argument: " + key);
			}
			return text;
		}

		static String optional(Map<String, Object> arguments, String key, String fallback) {
			Object value = arguments.get(key);
			return value instanceof String text && !text.isBlank() ? text : fallback;
		}

		static IWorkspaceRoot root() {
			return ResourcesPlugin.getWorkspace().getRoot();
		}

		static IProject project(String name) throws ToolExecutionException {
			IProject project = root().getProject(name);
			if (!project.exists()) {
				throw new ToolExecutionException("No workspace project named '" + name + "'");
			}
			return project;
		}

		static IProject openProject(String name) throws CoreException, ToolExecutionException {
			IProject project = project(name);
			if (!project.isOpen()) {
				project.open(new NullProgressMonitor());
			}
			return project;
		}

		static IResource resource(String path) throws ToolExecutionException {
			if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
				throw new ToolExecutionException("Path must be workspace-relative and must not contain '..': " + path);
			}
			IResource resource = root().findMember(path);
			if (resource == null || !resource.exists()) {
				throw new ToolExecutionException("No workspace resource at: " + path);
			}
			return resource;
		}

		static IFile file(String path) throws ToolExecutionException {
			IResource resource = resource(path);
			if (!(resource instanceof IFile file)) {
				throw new ToolExecutionException("Workspace resource is not a file: " + path);
			}
			return file;
		}

		static void ensureParents(IFile file) throws CoreException {
			if (file.getParent() instanceof IFolder folder && !folder.exists()) {
				ensureFolder(folder);
			}
		}

		static void ensureFolder(IFolder folder) throws CoreException {
			if (folder.getParent() instanceof IFolder parent && !parent.exists()) {
				ensureFolder(parent);
			}
			if (!folder.exists()) {
				folder.create(true, true, new NullProgressMonitor());
			}
		}
	}

	private static final class ListProjectsTool extends WorkspaceTool {
		@Override public String name() { return "workspace_list_projects"; }
		@Override public String description() { return "Lists projects currently present in the Eclipse workspace."; }
		@Override public Map<String, Object> inputSchema() { return Json.object("type", "object", "properties", Map.of()); }
		@Override Object executeWorkspace(Map<String, Object> arguments) {
			List<Object> projects = new ArrayList<>();
			for (IProject project : root().getProjects()) {
				projects.add(Json.object("name", project.getName(), "open", project.isOpen(), "accessible", project.isAccessible()));
			}
			return Json.object("workspace", root().getLocation() == null ? null : root().getLocation().toOSString(),
					"projects", projects);
		}
	}

	private static final class CreateJavaProjectTool extends WorkspaceTool {
		@Override public String name() { return "workspace_create_java_project"; }
		@Override public String description() { return "Creates a Java project with a source folder, output folder, Java nature and a JUnit-compatible classpath in the Eclipse workspace."; }
		@Override public Map<String, Object> inputSchema() {
			return Json.object("type", "object", "properties", Map.of("name", Map.of("type", "string"),
					"source_folder", Map.of("type", "string"), "output_folder", Map.of("type", "string")), "required", List.of("name"));
		}
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, ToolExecutionException {
			String name = required(arguments, "name");
			String source = optional(arguments, "source_folder", "src");
			String output = optional(arguments, "output_folder", "bin");
			IProject project = root().getProject(name);
			if (project.exists()) {
				throw new ToolExecutionException("Project already exists: " + name);
			}
			IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(name);
			description.setNatureIds(new String[] { "org.eclipse.jdt.core.javanature" });
			ICommand command = description.newCommand();
			command.setBuilderName("org.eclipse.jdt.core.javabuilder");
			description.setBuildSpec(new ICommand[] { command });
			project.create(description, new NullProgressMonitor());
			project.open(new NullProgressMonitor());
			ensureFolder(project.getFolder(source));
			ensureFolder(project.getFolder(output));
			IFile classpath = project.getFile(".classpath");
			String classpathXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
					+ "<classpath>\n  <classpathentry kind=\"src\" path=\"" + source + "\"/>\n"
					+ "  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21\"/>\n"
					+ "  <classpathentry kind=\"output\" path=\"" + output + "\"/>\n</classpath>\n";
			classpath.create(new ByteArrayInputStream(classpathXml.getBytes(StandardCharsets.UTF_8)), true, new NullProgressMonitor());
			return Json.object("created", true, "name", name, "sourceFolder", source, "outputFolder", output);
		}
	}

	private static final class ListFilesTool extends WorkspaceTool {
		@Override public String name() { return "workspace_list_files"; }
		@Override public String description() { return "Lists files and folders under a workspace project or path."; }
		@Override public Map<String, Object> inputSchema() { return Json.object("type", "object", "properties", Map.of("path", Map.of("type", "string")), "required", List.of("path")); }
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, ToolExecutionException {
			IResource base = resource(required(arguments, "path"));
			List<Object> files = new ArrayList<>();
			base.accept((IResourceVisitor) candidate -> {
				if (candidate != base) files.add(Json.object("path", candidate.getProjectRelativePath().toString(), "file", candidate instanceof IFile));
				return true;
			});
			return Json.object("count", files.size(), "resources", files);
		}
	}

	private static final class ReadFileTool extends WorkspaceTool {
		@Override public String name() { return "workspace_read_file"; }
		@Override public String description() { return "Reads a UTF-8 text file from the Eclipse workspace."; }
		@Override public Map<String, Object> inputSchema() { return Json.object("type", "object", "properties", Map.of("path", Map.of("type", "string")), "required", List.of("path")); }
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, IOException, ToolExecutionException {
			IFile file = file(required(arguments, "path"));
			try (var stream = file.getContents(); var output = new ByteArrayOutputStream()) {
				stream.transferTo(output);
				return Json.object("path", file.getFullPath().toString(), "content", output.toString(StandardCharsets.UTF_8));
			} catch (CoreException e) {
				throw new IOException(e.getMessage(), e);
			}
		}
	}

	private static final class WriteFileTool extends WorkspaceTool {
		@Override public String name() { return "workspace_write_file"; }
		@Override public String description() { return "Creates or overwrites a UTF-8 text file in the Eclipse workspace; parent folders are created automatically."; }
		@Override public Map<String, Object> inputSchema() { return Json.object("type", "object", "properties", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string"), "overwrite", Map.of("type", "boolean")), "required", List.of("path", "content")); }
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, ToolExecutionException {
			String path = required(arguments, "path");
			String content = required(arguments, "content");
			boolean overwrite = Boolean.TRUE.equals(arguments.get("overwrite"));
			if (path.startsWith("/") || path.contains("..")) throw new ToolExecutionException("Invalid workspace-relative path: " + path);
			IFile file = root().getFile(new org.eclipse.core.runtime.Path(path));
			ensureParents(file);
			if (file.exists()) {
				if (!overwrite) throw new ToolExecutionException("File exists; set overwrite=true: " + path);
				file.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, true, new NullProgressMonitor());
			} else {
				file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, new NullProgressMonitor());
			}
			return Json.object("written", true, "path", path, "bytes", content.getBytes(StandardCharsets.UTF_8).length);
		}
	}

	private static final class BuildProjectTool extends WorkspaceTool {
		@Override public String name() { return "workspace_build_project"; }
		@Override public String description() { return "Builds an Eclipse workspace project and returns the resulting problem markers."; }
		@Override public Map<String, Object> inputSchema() { return Json.object("type", "object", "properties", Map.of("name", Map.of("type", "string")), "required", List.of("name")); }
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, ToolExecutionException {
			IProject project = openProject(required(arguments, "name"));
			project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
			List<Object> markers = new ArrayList<>();
			for (IResource resource : project.members()) collectMarkers(resource, markers);
			return Json.object("built", true, "project", project.getName(), "markers", markers);
		}

		private static void collectMarkers(IResource resource, List<Object> markers) throws CoreException {
			for (var marker : resource.findMarkers(null, true, IResource.DEPTH_INFINITE)) {
				markers.add(Json.object("path", resource.getProjectRelativePath().toString(), "message", marker.getAttribute("message", ""), "severity", marker.getAttribute("severity", 0)));
			}
		}
	}

	private static final class RunJavaMainTool extends WorkspaceTool {
		@Override public String name() { return "workspace_run_java_main"; }
		@Override public String description() { return "Runs a validated Java main class from a built Eclipse workspace project and returns its output."; }
		@Override public Map<String, Object> inputSchema() {
			return Json.object("type", "object", "properties", Map.of("project", Map.of("type", "string"),
					"main_class", Map.of("type", "string"), "timeout_seconds", Map.of("type", "integer")),
					"required", List.of("project", "main_class"));
		}
		@Override Object executeWorkspace(Map<String, Object> arguments) throws CoreException, IOException, ToolExecutionException {
			IProject project = openProject(required(arguments, "project"));
			String mainClass = required(arguments, "main_class");
			if (!mainClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
				throw new ToolExecutionException("Invalid Java main class name: " + mainClass);
			}
			int timeout = arguments.get("timeout_seconds") instanceof Number number ? number.intValue() : 30;
			if (timeout < 1 || timeout > 300) throw new ToolExecutionException("timeout_seconds must be between 1 and 300");
			String outputPath = project.getLocation().append("bin").toOSString();
			Process process = new ProcessBuilder("java", "-cp", outputPath, mainClass).redirectErrorStream(true).start();
			boolean finished;
			try {
				finished = process.waitFor(timeout, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.destroyForcibly();
				throw new IOException("Java process was interrupted", e);
			}
			if (!finished) {
				process.destroyForcibly();
				return Json.object("project", project.getName(), "mainClass", mainClass, "timedOut", true, "exitCode", null);
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return Json.object("project", project.getName(), "mainClass", mainClass, "timedOut", false,
					"exitCode", process.exitValue(), "output", output);
		}
	}
}
