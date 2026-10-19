package devple.gow.tools.audio;

import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 오디오 도구.
 * <pre>
 *   audio:wav     &lt;file.VAG|file.VPK&gt; &lt;out.wav&gt;
 *   audio:all     &lt;gow-rom&gt; &lt;outDir&gt;      (모든 VAG/VPK 변환)
 *   audio:sbk     &lt;wad&gt; [outDir] [--grains]  SBlk 뱅크/VAG 팩/이미터 요약, outDir 이 있으면 톤·팩 항목 WAV 추출
 *   audio:sbk-all &lt;gow-rom&gt; &lt;outDir&gt;      전 WAD 뱅크 추출 + 신호 통계
 *   audio:music   &lt;gow-rom&gt;                MusicGroupStart 이름 → 뱅크 소리 → DPMS 스트림 표
 *   audio:hash    &lt;gow-rom&gt; &lt;hex...&gt;       해시 → SND 이름 (TWK 해시)
 * </pre>
 */
public final class Cli {

	public static void main(String[] a) throws Exception {
		switch (a[0]) {
			case "wav" -> convert(Path.of(a[1]), Path.of(a[2]));
			case "all" -> {
				Path out = Path.of(a[2]);
				Files.createDirectories(out);
				try (Stream<Path> s = Files.list(Path.of(a[1]))) {
					for (Path p : s.filter(p -> p.toString().endsWith(".VAG") || p.toString().endsWith(".VPK")).sorted().toList()) {
						convert(p, out.resolve(p.getFileName() + ".wav"));
					}
				}
			}
			case "sbk" -> sbk(Path.of(a[1]), a.length > 2 && !a[2].startsWith("--") ? Path.of(a[2]) : null,
					List.of(a).contains("--grains"));
			case "sbk-all" -> {
				for (Path p : wads(Path.of(a[1]))) {
					sbk(p, Path.of(a[2]).resolve(p.getFileName().toString().replace(".WAD", "")), false);
				}
			}
			case "music" -> music(Path.of(a[1]));
			case "hash" -> {
				Map<Integer, String> names = allNames(Path.of(a[1]));
				for (int i = 2; i < a.length; i++) {
					int h = Integer.parseUnsignedInt(a[i].replace("#", "").replace("0x", ""), 16);
					System.out.printf("%08x %s%n", h, names.getOrDefault(h, "?"));
				}
			}
			default -> throw new IllegalArgumentException(a[0]);
		}
	}

	private static List<Path> wads(Path rom) throws IOException {
		try (Stream<Path> s = Files.list(rom)) {
			return s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
	}

	/** 모든 뱅크 이름의 TWK 해시 (대문자 이름이라 hash == hashUpper). */
	static Map<Integer, String> allNames(Path rom) throws IOException {
		Map<Integer, String> out = new TreeMap<>();
		for (Path p : wads(rom)) {
			for (SoundBank b : WadSounds.load(WadFile.load(p)).banks()) {
				for (String n : b.names().keySet()) {
					out.putIfAbsent(TweakFile.hash(n), n);
					out.putIfAbsent(TweakFile.hashUpper(n), n);
				}
			}
		}
		return out;
	}

	private static void convert(Path in, Path out) throws Exception {
		PcmSound s = AudioDecoder.decode(in.getFileName().toString(), Files.readAllBytes(in));
		try (OutputStream os = Files.newOutputStream(out)) {
			s.writeWav(os);
		}
		System.out.printf("%-14s %5dHz %dch %6.1fs%n", s.name(), s.sampleRate(), s.channels(),
				s.samples().length / (double) s.channels() / s.sampleRate());
	}

	private static void sbk(Path wadPath, Path out, boolean grains) throws IOException {
		WadSounds ws = WadSounds.load(WadFile.load(wadPath));
		for (SoundBank b : ws.banks()) {
			System.out.printf("== %s %s%n", wadPath.getFileName(), b);
			Path dir = out != null ? out.resolve(b.name()) : null;
			if (dir != null) {
				Files.createDirectories(dir);
			}
			for (SoundBank.Sound s : b.sounds()) {
				List<SoundBank.Grain> gs = b.grains(s);
				StringBuilder sb = new StringBuilder();
				int k = 0;
				for (SoundBank.Grain g : gs) {
					if (g.type() == SoundBank.G_TONE) {
						SoundBank.Tone t = b.tone(g);
						AdpcmClip c = b.decode(t);
						sb.append(String.format(" tone[%x %.0fHz %.2fs%s]", t.sampleOffset(), t.sampleRate(), c.seconds(),
								c.looped() ? " loop" : ""));
						if (dir != null && c.pcm().length > 0) {
							write(dir.resolve(String.format("%03d_%s_%d.wav", s.index(), s.name() != null ? s.name() : "unnamed", k++)), c);
						}
					} else if (g.type() == SoundBank.G_PLUGIN) {
						SoundBank.Plugin p = b.plugin(g);
						if (p != null) {
							sb.append(p.command() == 0 ? String.format(" dpms[%s k%d%s]", p.name(), p.kind(), p.loop() ? " loop" : "")
									: String.format(" dpms[cmd%x %d %d]", p.command(), p.arg0(), p.arg1()));
						}
					} else if (g.type() == SoundBank.G_BRANCH || g.type() == SoundBank.G_START_CHILD) {
						SoundBank.SoundRef r = b.soundRef(g);
						sb.append(String.format(" %s->%d", g.type() == SoundBank.G_BRANCH ? "branch" : "child", r.soundIndex()));
					}
				}
				System.out.printf("  %3d %-28s vol=%3d vg=%d grains=%2d il=%d fl=%02x%s%n", s.index(), s.name(), s.vol(), s.volGroup(),
						s.numGrains(), s.instanceLimit(), s.flags(), sb);
				if (grains) {
					for (SoundBank.Grain g : gs) {
						System.out.printf("        t=%2d arg=%06x (%d,%d,%d) delay=%d%n", g.type(), g.arg(), g.arg(0), g.arg(1), g.arg(2), g.delay());
					}
				}
			}
		}
		for (VagPack p : ws.packs()) {
			System.out.printf("== %s VAG 팩 %s (%d)%n", wadPath.getFileName(), p.name(), p.entries().size());
			for (String e : p.entries().keySet()) {
				AdpcmClip c = p.clip(e);
				System.out.printf("  %-12s %5dHz %.2fs%n", e, c.rate(), c.seconds());
				if (out != null) {
					Path dir = out.resolve("pack_" + p.name());
					Files.createDirectories(dir);
					write(dir.resolve(e + ".wav"), c);
				}
			}
		}
		for (SoundEmitter e : ws.emitters().values()) {
			System.out.printf("  emitter %-28s %s mode=%d near=%.1f far=%.1f%n", e.name(), e.sounds(), e.mode(), e.near(), e.far());
		}
		ws.groups().forEach((g, s) -> System.out.printf("  group %-24s -> %s%n", g, s));
	}

	private static void write(Path p, AdpcmClip c) throws IOException {
		try (OutputStream os = Files.newOutputStream(p)) {
			c.toPcm().writeWav(os);
		}
	}

	/** 전 레벨 ESC 의 MusicGroupStart(문자열, 초) 인자 → 뱅크 소리 → DPMS 스트림. */
	private static void music(Path rom) throws IOException {
		Map<String, TreeSet<String>> users = new TreeMap<>();
		Map<String, String> target = new TreeMap<>();
		for (Path p : wads(rom)) {
			WadFile wad = WadFile.load(p);
			String wn = p.getFileName().toString().replace(".WAD", "");
			for (WadTag t : wad.resources()) {
				if (t.magic() != EscScript.MAGIC) {
					continue;
				}
				EscScript s;
				try {
					s = new EscScript(t.data());
				} catch (RuntimeException e) {
					continue;
				}
				for (EscScript.Entity e : s.entities) {
					for (EscScript.Handler h : e.handlers()) {
						List<EscOp> code = h.code();
						for (int i = 0; i < code.size(); i++) {
							if (code.get(i).text().contains("MusicGroupStart")) {
								for (int j = i - 1; j >= Math.max(0, i - 4); j--) {
									String tx = code.get(j).text();
									if (tx.startsWith("push_string ")) {
										users.computeIfAbsent(tx.substring(13, tx.length() - 1), k -> new TreeSet<>()).add(wn);
										break;
									}
								}
							}
						}
					}
				}
			}
			for (SoundBank b : WadSounds.load(wad).banks()) {
				for (String n : b.names().keySet()) {
					if (!users.containsKey(n) && !n.startsWith("SND_M_")) {
						continue;
					}
					SoundBank.Sound snd = b.sound(n);
					List<String> st = new ArrayList<>();
					for (SoundBank.Grain g : b.grains(snd)) {
						SoundBank.Plugin pl = g.type() == SoundBank.G_PLUGIN ? b.plugin(g) : null;
						if (pl != null && pl.command() == 0) {
							st.add(pl.name() + (pl.kind() >= 2 ? ".VPK" : ".VAG") + (pl.loop() ? "(loop)" : ""));
						}
					}
					target.putIfAbsent(n, String.join(" ", st));
				}
			}
		}
		TreeSet<String> all = new TreeSet<>(users.keySet());
		all.addAll(target.keySet());
		for (String n : all) {
			System.out.printf("%-26s %-40s %s%n", n, target.getOrDefault(n, "(뱅크에 없음)"), users.getOrDefault(n, new TreeSet<>()));
		}
	}
}
