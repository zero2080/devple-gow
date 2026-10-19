package devple.gow.tools.model;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Wavefront OBJ + MTL 출력. PS2 텍스처 좌표는 v 가 아래로 증가하므로 vt 는 (u, 1-v) 로 뒤집는다.
 * MTL 의 map_Kd 는 {@code <TXR 이름>.png} (텍스처 추출 도구가 같은 이름으로 PNG 를 만든다).
 */
public final class ObjWriter {

	private ObjWriter() {
	}

	/** @param textures 머티리얼 이름 → 첫 번째 TXR 이름 (없으면 null) */
	public static void write(TriMesh m, Map<String, String> textures, Path dir, boolean colors) throws IOException {
		Files.createDirectories(dir);
		String base = safe(m.name);
		Map<String, String> mats = new LinkedHashMap<>();
		for (TriMesh.Sub s : m.subs) {
			if (s.material != null) {
				mats.putIfAbsent(s.material, textures.get(s.material));
			}
		}
		if (!mats.isEmpty()) {
			try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve(base + ".mtl")))) {
				for (Map.Entry<String, String> e : mats.entrySet()) {
					w.println("newmtl " + e.getKey());
					w.println("Kd 1 1 1");
					if (e.getValue() != null) {
						w.println("map_Kd " + e.getValue() + ".png");
					}
					w.println();
				}
			}
		}
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve(base + ".obj")))) {
			w.println("# devple-gow model export: " + m.name);
			if (!mats.isEmpty()) {
				w.println("mtllib " + base + ".mtl");
			}
			int vbase = 1;
			int tbase = 1;
			int nbase = 1;
			for (TriMesh.Sub s : m.subs) {
				w.println("o " + s.name);
				for (int i = 0; i < s.pos.size(); i++) {
					float[] p = s.pos.get(i);
					if (colors && !s.color.isEmpty()) {
						int[] c = s.color.get(i);
						w.printf(Locale.ROOT, "v %.5f %.5f %.5f %.4f %.4f %.4f%n", p[0], p[1], p[2],
								Math.min(1f, c[0] / 128f), Math.min(1f, c[1] / 128f), Math.min(1f, c[2] / 128f));
					} else {
						w.printf(Locale.ROOT, "v %.5f %.5f %.5f%n", p[0], p[1], p[2]);
					}
				}
				if (s.hasUv) {
					for (float[] t : s.uv) {
						w.printf(Locale.ROOT, "vt %.5f %.5f%n", t[0], 1 - t[1]);
					}
				}
				if (s.hasNormal) {
					for (float[] n : s.normal) {
						w.printf(Locale.ROOT, "vn %.4f %.4f %.4f%n", n[0], n[1], n[2]);
					}
				}
				if (s.material != null) {
					w.println("usemtl " + s.material);
				}
				for (int[] t : s.tris) {
					w.print("f");
					for (int i : t) {
						int v = vbase + i;
						int vt = tbase + i;
						int vn = nbase + i;
						if (s.hasUv && s.hasNormal) {
							w.print(" " + v + "/" + vt + "/" + vn);
						} else if (s.hasUv) {
							w.print(" " + v + "/" + vt);
						} else if (s.hasNormal) {
							w.print(" " + v + "//" + vn);
						} else {
							w.print(" " + v);
						}
					}
					w.println();
				}
				vbase += s.pos.size();
				tbase += s.hasUv ? s.pos.size() : 0;
				nbase += s.hasNormal ? s.pos.size() : 0;
			}
		}
	}

	static String safe(String s) {
		return s.replaceAll("[^A-Za-z0-9_.-]", "_");
	}
}
