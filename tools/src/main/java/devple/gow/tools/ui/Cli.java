package devple.gow.tools.ui;

import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * UI / 텍스트 / 스크립트 분석 명령.
 * <pre>
 *   ui:info   &lt;wad&gt;                FLP 요약, 폰트, 동적 라벨(→ 메시지), 스크립트 리소스 수
 *   ui:text   &lt;wad&gt; &lt;outFile&gt;      메시지 테이블 + FLP 라벨 텍스트 + 폰트 문자표를 UTF-8 로 저장
 *   ui:flp    &lt;wad&gt;                FLP 타임라인/키프레임/액션 스크립트 전체 덤프
 *   ui:script &lt;wad&gt;                SCR/ESC 스크립트 덤프 (엔티티 + 핸들러 디스어셈블)
 *   ui:script-stats &lt;romDir&gt; &lt;outFile&gt;  레벨별 엔티티 타입/엔진 함수 호출 통계
 * </pre>
 * 메시지 테이블은 R_PERMK.WAD 에만 있으므로 다른 WAD 는 같은 디렉토리의 R_PERMK.WAD 를 읽어 해석한다.
 */
public final class Cli {

	private Cli() {
	}

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "info" -> {
				for (int i = 1; i < args.length; i++) {
					info(Path.of(args[i]));
				}
			}
			case "text" -> text(Path.of(args[1]), Path.of(args[2]));
			case "flp" -> flp(Path.of(args[1]), System.out);
			case "script" -> script(Path.of(args[1]), System.out);
			case "script-stats" -> scriptStats(Path.of(args[1]), Path.of(args[2]));
			default -> throw new IllegalArgumentException("unknown ui command: " + args[0]);
		}
	}

	static Optional<MessageTable> messages(Path wadPath, WadFile wad) throws IOException {
		Optional<MessageTable> m = MessageTable.fromWad(wad);
		if (m.isPresent()) {
			return m;
		}
		Path perm = wadPath.resolveSibling("R_PERMK.WAD");
		return Files.exists(perm) ? MessageTable.fromWad(WadFile.load(perm)) : Optional.empty();
	}

	private static Map<String, FlpFile> flps(WadFile wad) {
		Map<String, FlpFile> out = new TreeMap<>();
		for (WadTag t : wad.resources()) {
			if (t.magic() == FlpFile.MAGIC) {
				out.put(t.name(), new FlpFile(t.data()));
			}
		}
		return out;
	}

	private static void info(Path p) throws IOException {
		WadFile wad = WadFile.load(p);
		Optional<MessageTable> msgs = messages(p, wad);
		System.out.printf("%s  messages=%s%n", wad.name(), msgs.map(m -> m.all().size() + "개").orElse("없음"));
		for (var e : flps(wad).entrySet()) {
			FlpFile f = e.getValue();
			System.out.printf("%s: handlers=%d mesh=%d fonts=%d static=%d dynamic=%d sprites=%d clips=%d root(frames=%d layers=%d labels=%d) xf=%d colors=%d strings=%d consistent=%s%n",
					e.getKey(), f.handlers.size(), f.meshRefs.size(), f.fonts.size(), f.staticLabels.size(), f.dynamicLabels.size(),
					f.sprites.size(), f.clips.size(), f.root.totalFrames(), f.root.layers().size(), f.root.labels().size(),
					f.transforms.size(), f.colors.size(), f.strings.length, f.consistent);
			for (FlpFile.Font font : f.fonts) {
				TreeSet<String> tex = new TreeSet<>();
				font.glyphMeshes().forEach(m -> m.materials().forEach(mt -> tex.add(String.valueOf(mt.texture()))));
				System.out.printf("  font glyphs=%d size=%d flags=%d unk=(%d,%d,%d) scale=%s textures=%s%n",
						font.glyphCount(), font.size(), font.flags(), font.unk04(), font.unk08(), font.unk0a(), font.scale(), tex);
			}
			for (FlpFile.DynamicLabel d : f.dynamicLabels) {
				String msg = msgs.map(m -> m.forVariable(d.variable())).orElse(null);
				System.out.printf("  dyn %-28s font=%-8s h=%3d align=%d rect=%s  %s%n", d.variable(), f.handlerName(d.fontHandler()),
						d.fontHeight(), d.align(), java.util.Arrays.toString(d.rect()), msg == null ? "" : "\"" + oneLine(msg) + "\"");
			}
		}
		Map<String, Integer> scr = new TreeMap<>();
		for (WadTag t : wad.resources()) {
			if (t.magic() == EscScript.MAGIC) {
				scr.merge(new EscScript(t.data()).target, 1, Integer::sum);
			}
		}
		System.out.println("scripts: " + scr);
	}

	private static String oneLine(String s) {
		return s.replace("\n", "\\n");
	}

	private static void text(Path p, Path out) throws IOException {
		WadFile wad = WadFile.load(p);
		Optional<MessageTable> msgs = messages(p, wad);
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		try (PrintStream o = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
			if (MessageTable.fromWad(wad).isPresent()) {
				o.println("# 메시지 테이블 (" + msgs.get().all().size() + "개)");
				msgs.get().all().forEach((id, s) -> o.printf("*%d*%n%s%n", id, s));
			}
			for (var e : flps(wad).entrySet()) {
				FlpFile f = e.getValue();
				o.println("# " + e.getKey() + " 동적 라벨 (변수 → 메시지)");
				for (FlpFile.DynamicLabel d : f.dynamicLabels) {
					String msg = msgs.map(m -> m.forVariable(d.variable())).orElse(null);
					o.printf("%s\t%s%n", d.variable(), msg == null ? "" : oneLine(msg));
				}
				if (!f.staticLabels.isEmpty()) {
					o.println("# " + e.getKey() + " 정적 라벨");
					f.staticLabels.forEach(sl -> o.println(oneLine(f.staticText(sl))));
				}
				for (int i = 0; i < f.fonts.size(); i++) {
					FlpFile.Font font = f.fonts.get(i);
					o.printf("# %s font %d 글리프표 (index\tU+code\tchar\twidth\tmeshPart\ttexture)%n", e.getKey(), i);
					for (int g = 0; g < font.glyphCount(); g++) {
						char c = font.charOf(g);
						FlpFile.MeshRef m = g < font.glyphMeshes().size() ? font.glyphMeshes().get(g) : null;
						o.printf("%d\tU+%04X\t%s\t%d\t%s\t%s%n", g, (int) c, Character.isISOControl(c) ? "" : String.valueOf(c),
								font.widths()[g], m == null ? "" : m.meshPart(),
								m == null || m.materials().isEmpty() ? "" : m.materials().get(0).texture());
					}
					if (msgs.isPresent()) {
						// 메시지 본문(치환 태그 제외) 중 이 폰트에 없는 문자
						TreeSet<Character> missing = new TreeSet<>();
						for (String s : msgs.get().all().values()) {
							for (char c : s.replaceAll("\\[[^\\]]*\\]", "").toCharArray()) {
								if (c != '\n' && font.glyphOf(c) < 0) {
									missing.add(c);
								}
							}
						}
						o.println("# font " + i + " 에 없는 메시지 문자: " + missing);
					}
				}
			}
		}
		System.out.println("wrote " + out);
	}

	private static void flp(Path p, PrintStream o) throws IOException {
		WadFile wad = WadFile.load(p);
		Optional<MessageTable> msgs = messages(p, wad);
		for (var e : flps(wad).entrySet()) {
			FlpFile f = e.getValue();
			o.println("=== " + e.getKey());
			for (int i = 0; i < f.meshRefs.size(); i++) {
				FlpFile.MeshRef m = f.meshRefs.get(i);
				o.printf("mesh#%d part=%d %s%n", i, m.meshPart(),
						m.materials().stream().map(mt -> String.format("%08x:%s", mt.color(), mt.texture())).toList());
			}
			for (int i = 0; i < f.dynamicLabels.size(); i++) {
				FlpFile.DynamicLabel d = f.dynamicLabels.get(i);
				String msg = msgs.map(m -> m.forVariable(d.variable())).orElse(null);
				o.printf("dynamic#%d var=%s ph=%s font=%s h=%d color=%08x max=%d margins=%s leading=%d bounds=%s align=%d flags=%02x %s%n",
						i, d.variable(), d.placeholder(), f.handlerName(d.fontHandler()), d.fontHeight(), d.color(), d.maxLength(),
						java.util.Arrays.toString(d.margins()), d.leading(), java.util.Arrays.toString(d.rect()), d.align(), d.flags(),
						msg == null ? "" : "\"" + oneLine(msg) + "\"");
			}
			for (int i = 0; i < f.sprites.size(); i++) {
				FlpFile.Sprite s = f.sprites.get(i);
				o.printf("sprite#%d%n", i);
				timeline(f, s.timeline(), o);
				for (FlpFile.InputHandler ih : s.inputs()) {
					o.printf("  on input mask=0x%x unk=%d%n", ih.keyMask(), ih.unk());
					FlpScript.disassemble(ih.script(), f::scriptStr).forEach(l -> o.println("      " + l));
				}
			}
			for (int i = 0; i < f.clips.size(); i++) {
				o.printf("clip#%d%n", i);
				timeline(f, f.clips.get(i), o);
			}
			o.println("root");
			timeline(f, f.root, o);
		}
	}

	private static void timeline(FlpFile f, FlpFile.Timeline t, PrintStream o) {
		o.printf("  frames=%d bounds=%s%n", t.totalFrames(), java.util.Arrays.toString(t.bounds()));
		for (int li = 0; li < t.layers().size(); li++) {
			FlpFile.Layer l = t.layers().get(li);
			o.printf("  layer%d frames=%d%n", li, l.frames());
			for (FlpFile.KeyFrame k : l.keys()) {
				String xf = "";
				if (k.transform() < f.transforms.size()) {
					FlpFile.Transform x = f.transforms.get(k.transform());
					xf = String.format(" xf=[%.3f %.3f %.3f %.3f | %.1f,%.1f]", x.a(), x.b(), x.c(), x.d(), x.x(), x.y());
				}
				String col = k.color() < f.colors.size() ? " col=" + java.util.Arrays.toString(f.colors.get(k.color())) : "";
				o.printf("    ..%d %s%s%s%s%n", k.untilFrame(), f.handlerName(k.handler()), k.name() != null ? " '" + k.name() + "'" : "", xf, col);
			}
		}
		for (FlpFile.FrameLabel fl : t.labels()) {
			o.printf("  frame %d label=%s%n", fl.frame(), fl.name());
			for (byte[] sc : fl.scripts()) {
				FlpScript.disassemble(sc, f::scriptStr).forEach(l -> o.println("      " + l));
			}
		}
	}

	private static void script(Path p, PrintStream o) throws IOException {
		WadFile wad = WadFile.load(p);
		Optional<MessageTable> msgs = messages(p, wad);
		for (WadTag t : wad.resources()) {
			if (t.magic() != EscScript.MAGIC) {
				continue;
			}
			EscScript s = new EscScript(t.data());
			o.printf("=== %s target=%s unk=(%x,%x,%x) body=%d%n", t.name(), s.target, s.unk1e, s.unk20, s.unk22, s.body.length);
			for (EscScript.Entity e : s.entities) {
				o.printf("entity #%d %s '%s' base=%d param=%d unk40=%d pos=(%.1f,%.1f,%.1f) targets=%s%n", e.id(), e.typeName(), e.name(),
						e.baseId(), e.param(), e.unk40(), e.matrix()[12], e.matrix()[13], e.matrix()[14], e.targets());
				for (String[] v : e.variables()) {
					o.printf("  var %s %s%n", v[0], v[1]);
				}
				for (EscScript.Handler h : e.handlers()) {
					o.printf("  handler %d%n", h.id());
					boolean showsMsg = h.code().stream().anyMatch(op -> op.text().contains("ShowMessage"));
					for (EscOp op : h.code()) {
						String extra = "";
						if (showsMsg && op.text().startsWith("push_int ") && msgs.isPresent()) {
							// ShowMessage 를 호출하는 핸들러의 정수 상수는 메시지 ID 로 보고 본문을 곁들인다
							String m = msgs.get().get(Integer.parseInt(op.text().substring(9)));
							if (m != null && Integer.parseInt(op.text().substring(9)) >= 100) {
								extra = "   ; msg \"" + oneLine(m) + "\"";
							}
						}
						o.printf("    @%04x %s%s%n", op.offset(), op.text(), extra);
					}
				}
			}
		}
	}

	private static void scriptStats(Path romDir, Path out) throws IOException {
		Map<String, Map<String, Integer>> funcs = new TreeMap<>();
		Map<String, Integer> total = new TreeMap<>();
		Map<String, Integer> types = new TreeMap<>();
		Map<String, Integer> targets = new TreeMap<>();
		int scripts = 0, entities = 0;
		try (Stream<Path> files = Files.list(romDir)) {
			for (Path p : files.filter(x -> x.toString().endsWith(".WAD")).sorted().toList()) {
				WadFile wad = WadFile.load(p);
				Map<String, Integer> lv = new TreeMap<>();
				for (WadTag t : wad.resources()) {
					if (t.magic() != EscScript.MAGIC) {
						continue;
					}
					EscScript s = new EscScript(t.data());
					scripts++;
					targets.merge(s.target, 1, Integer::sum);
					for (EscScript.Entity e : s.entities) {
						entities++;
						types.merge(e.typeName(), 1, Integer::sum);
						for (EscScript.Handler h : e.handlers()) {
							for (EscOp op : h.code()) {
								if (op.text().startsWith("call_") || op.text().startsWith("?")) {
									String k = op.text().substring(op.text().indexOf(' ') + 1);
									if (op.text().startsWith("?")) {
										k = op.text();
									}
									lv.merge(k, 1, Integer::sum);
									total.merge(k, 1, Integer::sum);
								}
							}
						}
					}
				}
				if (!lv.isEmpty()) {
					funcs.put(wad.name(), lv);
				}
			}
		}
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		try (PrintStream o = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
			o.printf("# scripts=%d entities=%d%n# targets %s%n# entity types %s%n# 전체 함수 호출 빈도%n", scripts, entities, targets, types);
			total.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.forEach(e -> o.printf("%6d %s%n", e.getValue(), e.getKey()));
			o.println("# 레벨별");
			funcs.forEach((w, m) -> o.printf("%s %s%n", w, m));
		}
		System.out.println("wrote " + out);
	}
}
