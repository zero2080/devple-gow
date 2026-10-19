package devple.gow.tools.model;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 모델/메시 도구.
 * <pre>
 *   model:info   &lt;wad&gt;
 *   model:export &lt;wad&gt; &lt;outDir&gt; [--lod N] [--colors] [--preview]
 *   model:render &lt;wad&gt; &lt;MDL_이름&gt; &lt;out.png&gt; [--lod N]
 *   model:batch  &lt;romDir&gt; [outDir] [--preview]     모든 WAD 일괄 처리 + 실패 통계
 * </pre>
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		if (args.length < 2) {
			System.err.println("usage: model:<info|export|render|batch> <wad|dir> ...");
			System.exit(1);
		}
		List<String> a = new ArrayList<>(Arrays.asList(args));
		int lod = intOpt(a, "--lod", 0);
		boolean colors = a.remove("--colors");
		boolean preview = a.remove("--preview");
		switch (a.get(0)) {
			case "info" -> info(WadFile.load(Path.of(a.get(1))));
			case "export" -> {
				Stats st = export(WadFile.load(Path.of(a.get(1))), Path.of(a.get(2)), lod, colors, preview, true);
				System.out.println(st);
			}
			case "render" -> {
				WadFile w = WadFile.load(Path.of(a.get(1)));
				GowModel m = GowModel.load(w).stream().filter(x -> x.name.equals(a.get(2))).findFirst().orElseThrow();
				TriMesh t = TriMesh.of(m, lod);
				PreviewRenderer.render(t, Path.of(a.get(3)), 512);
				System.out.printf("%s verts=%d tris=%d bounds=%s winding=%.3f%n", m.name, t.vertexCount(), t.triangleCount(),
						Arrays.toString(t.bounds()), t.windingAgreement());
			}
			case "batch" -> batch(Path.of(a.get(1)), a.size() > 2 ? Path.of(a.get(2)) : null, lod, colors, preview);
			default -> throw new IllegalArgumentException("unknown command: " + a.get(0));
		}
	}

	private static int intOpt(List<String> a, String key, int def) {
		int i = a.indexOf(key);
		if (i < 0) {
			return def;
		}
		int v = Integer.parseInt(a.get(i + 1));
		a.remove(i + 1);
		a.remove(i);
		return v;
	}

	private static void info(WadFile wad) {
		for (GowModel m : GowModel.load(wad)) {
			System.out.printf("%s  materials=%d/%d meshes=%d/%d joints=%d%n", m.name, m.materials.size(), m.materialCount,
					m.meshes.size(), m.meshCount, m.jointCount);
			for (int i = 0; i < m.materials.size(); i++) {
				System.out.printf("  mat[%d] %s %s%n", i, m.materials.get(i), GowModel.textureNames(wad, m.materials.get(i)));
			}
			m.errors.forEach(e -> System.out.println("  ERROR " + e));
			for (GowMesh mesh : m.meshes) {
				System.out.printf("  mesh %s parts=%d joints=%d records=%d%n", mesh.name, mesh.parts.size(), mesh.jointCount, mesh.recordCount);
				for (int p = 0; p < mesh.parts.size(); p++) {
					GowMesh.Part part = mesh.parts.get(p);
					System.out.printf("    part[%d] @%x jointRefs=%s lods=%d%n", p, part.offset(), Arrays.toString(part.jointRefs()), part.groups().size());
					for (int g = 0; g < part.groups().size(); g++) {
						GowMesh.Group grp = part.groups().get(g);
						System.out.printf("      lod[%d] dist=%g bounds=%b objects=%d%n", g, grp.lodDistance(), grp.hasBounds(), grp.objects().size());
						for (MeshObject o : grp.objects()) {
							System.out.printf("        obj @%x type=%x mat=%d flags=%d chains=%d batches=%d verts=%d tris=%d joints=%s%s%n",
									o.offset, o.type, o.materialIndex, o.flags, o.chainCount, o.batches.size(), o.vertexCount(),
									o.triangleCount(), Arrays.toString(o.jointMap), o.warnings.isEmpty() ? "" : " WARN " + o.warnings);
						}
					}
				}
			}
		}
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.MESH_EXTRA && t.name().startsWith("MSH_")) {
				MshMesh s = new MshMesh(t.name(), t.data());
				System.out.printf("%s (0x70) verts=%d tris=%d%n", s.name, s.pos.length, s.tris.length / 3);
			}
		}
	}

	/** 통계 누적. */
	static final class Stats {
		int wads, models, meshes, objects, batches, verts, tris, exported, failedModels, emptyModels, warnObjects, msh;
		final Map<String, Integer> failures = new TreeMap<>();
		final Map<String, String> examples = new HashMap<>();

		void fail(String key) {
			failures.merge(key, 1, Integer::sum);
		}

		void fail(String key, String example) {
			fail(key);
			examples.putIfAbsent(key, example);
		}

		@Override
		public String toString() {
			return String.format("wads=%d models=%d meshes=%d objects=%d batches=%d verts=%d tris=%d exported=%d msh=%d failedModels=%d emptyModels=%d warnObjects=%d%s",
					wads, models, meshes, objects, batches, verts, tris, exported, msh, failedModels, emptyModels, warnObjects,
					failures.isEmpty() ? "" : "\n  " + String.join("\n  ", failures.entrySet().stream().map(e -> e.getValue() + " × " + e.getKey()
							+ (examples.containsKey(e.getKey()) ? "  (예: " + examples.get(e.getKey()) + ")" : "")).toList()));
		}
	}

	static Stats export(WadFile wad, Path out, int lod, boolean colors, boolean preview, boolean verbose) {
		Stats st = new Stats();
		export(wad, out, lod, colors, preview, verbose, st);
		return st;
	}

	private static void export(WadFile wad, Path out, int lod, boolean colors, boolean preview, boolean verbose, Stats st) {
		st.wads++;
		Map<String, String> tex = new HashMap<>();
		List<GowModel> models;
		try {
			models = GowModel.load(wad);
		} catch (RuntimeException e) {
			st.fail(wad.name() + ": " + e);
			return;
		}
		for (GowModel m : models) {
			st.models++;
			try {
				if (!m.errors.isEmpty()) {
					st.failedModels++;
					m.errors.forEach(e -> st.fail(wad.name() + "/" + e));
					continue;
				}
				st.meshes += m.meshes.size();
				for (GowMesh mesh : m.meshes) {
					for (GowMesh.Part p : mesh.parts) {
						for (GowMesh.Group g : p.groups()) {
							for (MeshObject o : g.objects()) {
								st.objects++;
								st.batches += o.batches.size();
								if (!o.warnings.isEmpty()) {
									st.warnObjects++;
									o.warnings.forEach(w -> st.fail("warn: " + w.replaceAll("@[0-9a-f]+", ""),
											String.format("%s/%s @%x", wad.name(), mesh.name, o.offset)));
								}
							}
						}
					}
				}
				TriMesh t = TriMesh.of(m, lod);
				st.verts += t.vertexCount();
				st.tris += t.triangleCount();
				if (t.triangleCount() == 0) {
					st.emptyModels++;
					st.fail(m.meshes.isEmpty() ? "empty: 메시 없음" : "empty: 정점 없음", wad.name() + "/" + m.name);
					continue;
				}
				for (String mat : m.materials) {
					tex.computeIfAbsent(mat, k -> {
						List<String> l = GowModel.textureNames(wad, k);
						return l.isEmpty() ? null : l.get(0);
					});
				}
				if (out != null) {
					ObjWriter.write(t, tex, out, colors);
					if (preview) {
						PreviewRenderer.render(t, out.resolve(ObjWriter.safe(m.name) + ".png"), 384);
					}
				}
				st.exported++;
				if (verbose) {
					System.out.printf("%-28s verts=%6d tris=%6d winding=%.3f%n", m.name, t.vertexCount(), t.triangleCount(), t.windingAgreement());
				}
			} catch (Exception e) {
				st.failedModels++;
				st.fail(wad.name() + "/" + m.name + ": " + e);
			}
		}
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.MESH_EXTRA && t.name().startsWith("MSH_")) {
				try {
					TriMesh tm = TriMesh.of(new MshMesh(t.name(), t.data()));
					st.msh++;
					if (out != null) {
						ObjWriter.write(tm, Map.of(), out, false);
					}
				} catch (Exception e) {
					st.fail(wad.name() + "/" + t.name() + ": " + e);
				}
			}
		}
	}

	private static void batch(Path dir, Path out, int lod, boolean colors, boolean preview) throws Exception {
		Stats st = new Stats();
		List<Path> wads;
		try (Stream<Path> s = Files.list(dir)) {
			wads = s.filter(p -> p.getFileName().toString().toUpperCase().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			WadFile w;
			try {
				w = WadFile.load(p);
			} catch (Exception e) {
				st.fail(p.getFileName() + ": " + e);
				continue;
			}
			int before = st.failedModels;
			export(w, out == null ? null : out.resolve(p.getFileName().toString().replace(".WAD", "")), lod, colors, preview, false, st);
			System.out.printf("%-14s models=%d failed=%d%n", p.getFileName(), st.models, st.failedModels - before);
		}
		System.out.println(st);
	}
}
