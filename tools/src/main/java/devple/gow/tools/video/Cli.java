package devple.gow.tools.video;

import devple.gow.tools.pak.GowDisc;
import devple.gow.tools.pak.TocEntry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 동영상(PSS/PSW) 도구 (18·33 문서). src = ISO 경로 또는 PSS 파일 / 디렉터리.
 * <pre>
 *   video:extract &lt;iso&gt; &lt;outDir&gt; NAME.PSS...       ISO 에서 몇 개만 꺼내기
 *   video:info    &lt;iso|file&gt; [NAME.PSS]            팩/PES 통계, 시퀀스 헤더, SShd
 *   video:survey  &lt;iso&gt;                            전 동영상 헤더 요약 (앞 256KB 만 읽음)
 *   video:names   &lt;gow-rom&gt;                        레벨 스크립트(ESC)의 PlayMovie/MovieCtl/PreloadMovie 호출 목록
 *   video:frames  &lt;iso|file&gt; NAME.PSS &lt;outDir&gt; t1 t2 ...   해당 시각(초) 프레임 PNG
 *   video:bench   &lt;iso|file&gt; NAME.PSS [초]           디코딩 속도 (fps)
 *   video:wav     &lt;iso|file&gt; NAME.PSS &lt;out.wav&gt;      오디오 트랙 WAV
 *   video:export  &lt;iso|dir&gt; NAME.PSS &lt;out.mov&gt;       QuickTime 재생용 .mov (H.264 하드웨어 + PCM)
 *   video:export-all &lt;dir&gt; &lt;outDir&gt; [--wide]          전부 변환 (기본 PSS, --wide 면 PSW). 이미 있으면 건너뜀
 * </pre>
 */
public final class Cli {

	public static void main(String[] a) throws Exception {
		switch (a[0]) {
			case "extract" -> {
				Path out = Path.of(a[2]);
				Files.createDirectories(out);
				try (GowDisc disc = new GowDisc(Path.of(a[1]))) {
					for (int i = 3; i < a.length; i++) {
						TocEntry e = disc.toc().unique().get(a[i]);
						try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out.resolve(a[i])))) {
							disc.copyTo(e, os);
						}
					}
				}
			}
			case "info" -> {
				try (MovieSource s = open(a[1], a.length > 2 ? a[2] : null)) {
					info(s, Long.MAX_VALUE, true);
				}
			}
			case "survey" -> survey(Path.of(a[1]));
			case "names" -> names(Path.of(a[1]));
			case "frames" -> {
				List<Double> times = new ArrayList<>();
				for (int i = 4; i < a.length; i++) {
					times.add(Double.parseDouble(a[i]));
				}
				try (MovieSource s = open(a[1], a[2])) {
					frames(s, Path.of(a[3]), times);
				}
			}
			case "bench" -> {
				try (MovieSource s = open(a[1], a[2])) {
					bench(s, a.length > 3 ? Double.parseDouble(a[3]) : 1e9);
				}
			}
			case "export" -> {
				Path helper = MovieExporter.helper(Path.of("").toAbsolutePath());
				try (MovieSource s = open(a[1], a[2])) {
					long t0 = System.nanoTime();
					int n = MovieExporter.export(s, Path.of(a[3]), helper, a[2].toUpperCase().endsWith(".PSW"));
					System.out.printf("%s → %s: %d 프레임, %.1f초%n", a[2], a[3], n, (System.nanoTime() - t0) / 1e9);
				}
			}
			case "export-all" -> {
				Path helper = MovieExporter.helper(Path.of("").toAbsolutePath());
				boolean wide = List.of(a).contains("--wide");
				Path outDir = Path.of(a[2]);
				Files.createDirectories(outDir);
				List<Path> files;
				try (var st = Files.list(Path.of(a[1]))) {
					files = st.filter(f -> f.getFileName().toString().toUpperCase().endsWith(wide ? ".PSW" : ".PSS")).sorted().toList();
				}
				int i = 0;
				for (Path f : files) {
					i++;
					String base = f.getFileName().toString().replaceFirst("\\.[^.]+$", "");
					Path out = outDir.resolve(base + (wide ? "_wide" : "") + ".mov");
					if (Files.exists(out)) {
						continue;
					}
					long t0 = System.nanoTime();
					try (MovieSource s = MovieSource.ofFile(f)) {
						int n = MovieExporter.export(s, out, helper, wide);
						System.out.printf("[%d/%d] %s: %d 프레임, %.1f초, %.1f MB%n", i, files.size(), out.getFileName(), n,
								(System.nanoTime() - t0) / 1e9, Files.size(out) / 1e6);
					}
				}
			}
			case "wav" -> {
				try (MovieSource s = open(a[1], a[2])) {
					wav(s, Path.of(a[3]));
				}
			}
			default -> throw new IllegalArgumentException(a[0]);
		}
	}

	static MovieSource open(String src, String name) throws IOException {
		Path p = Path.of(src);
		if (Files.isDirectory(p)) {
			return MovieSource.ofFile(p.resolve(name));
		}
		if (src.toLowerCase().endsWith(".iso")) {
			GowDisc disc = new GowDisc(p);
			TocEntry e = disc.toc().unique().get(name.toUpperCase());
			if (e == null) {
				disc.close();
				throw new IOException("TOC 에 없음: " + name);
			}
			return MovieSource.ofDisc(disc, e, true);
		}
		return MovieSource.ofFile(p);
	}

	/** limit 바이트까지 훑어 스트림 구성 출력. */
	static String info(MovieSource s, long limit, boolean verbose) throws IOException {
		PssDemuxer d = new PssDemuxer(s);
		SsAudio audio = new SsAudio();
		int[] count = new int[2];
		long[] bytes = new long[2];
		long firstV = -1, firstA = -1, lastV = -1, lastA = -1;
		Map<Integer, Integer> subs = new java.util.TreeMap<>();
		Mpeg2Video seq = new Mpeg2Video(f -> {
		});
		PssDemuxer.Packet p;
		while ((p = d.next()) != null && d.position() < limit) {
			int k = p.stream == PssDemuxer.VIDEO ? 0 : 1;
			count[k]++;
			bytes[k] += p.len;
			if (k == 0) {
				if (seq.width() == 0) {
					seq.feed(p.data, 0, Math.min(p.len, 64));
				}
				if (p.pts >= 0) {
					firstV = firstV < 0 ? p.pts : firstV;
					lastV = p.pts;
				}
			} else {
				subs.merge(p.sub, 1, Integer::sum);
				if (p.sub == 0xFF) {
					audio.feed(p.data, 0, p.len);
					audio.drain(new short[audio.available()], 0, audio.available());
				}
				if (p.pts >= 0) {
					firstA = firstA < 0 ? p.pts : firstA;
					lastA = p.pts;
				}
			}
		}
		String line = String.format("%-12s %10d  video %dx%d aspect=%d fps=%.3f  audio %s  vPTS0=%.3f aPTS0=%.3f",
				s.name(), s.size(), seq.width(), seq.height(), seq.aspectCode(), seq.fps(), audio, firstV / 90000.0, firstA / 90000.0);
		if (verbose) {
			System.out.println(line);
			System.out.printf("  video PES %d (%d B), audio PES %d (%d B), sub ids %s, last vPTS %.3f aPTS %.3f%n",
					count[0], bytes[0], count[1], bytes[1], subs, lastV / 90000.0, lastA / 90000.0);
		}
		return line;
	}

	static void survey(Path iso) throws IOException {
		try (GowDisc disc = new GowDisc(iso)) {
			for (TocEntry e : disc.toc().unique().values()) {
				if (e.name().endsWith(".PSS") || e.name().endsWith(".PSW")) {
					try (MovieSource s = MovieSource.ofDisc(disc, e, false)) {
						System.out.println(info(s, 256 * 1024, false));
					}
				}
			}
		}
	}

	/** 모든 WAD 의 ESC 핸들러에서 동영상 함수 호출과 직전 push 를 출력. */
	static void names(Path rom) throws IOException {
		List<Path> wads;
		try (var st = Files.list(rom)) {
			wads = st.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path w : wads) {
			devple.gow.tools.wad.WadFile wad = devple.gow.tools.wad.WadFile.load(w);
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (devple.gow.tools.wad.WadTag t : wad.resources()) {
				if (t.magic() != devple.gow.tools.script.EscScript.MAGIC || !t.name().startsWith("ESC_") || !seen.add(t.name())) {
					continue;
				}
				devple.gow.tools.script.EscScript sc = new devple.gow.tools.script.EscScript(t.data());
				for (devple.gow.tools.script.EscScript.Entity e : sc.entities) {
					for (devple.gow.tools.script.EscScript.Handler h : e.handlers()) {
						List<devple.gow.tools.script.EscOp> c = h.code();
						for (int i = 0; i < c.size(); i++) {
							String tx = c.get(i).text();
							if (tx.contains("Movie")) {
								StringBuilder sb = new StringBuilder();
								for (int k = Math.max(0, i - 3); k < i; k++) {
									sb.append(c.get(k).text()).append(" ; ");
								}
								System.out.printf("%-10s %-24s %-28s h%-3d %s=> %s%n", w.getFileName(), t.name(), e.name(), h.id(), sb, tx);
							}
						}
					}
				}
			}
		}
	}

	static void frames(MovieSource s, Path outDir, List<Double> times) throws IOException {
		Files.createDirectories(outDir);
		double maxT = times.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		List<VideoFrame> keep = new ArrayList<>();
		boolean[] done = {false};
		Mpeg2Video v = new Mpeg2Video(f -> {
			for (double t : times) {
				if (Math.abs(f.time() - t) < 0.5 / 29.97) {
					keep.add(f);
				}
			}
			done[0] |= f.time() > maxT;
		});
		PssDemuxer d = new PssDemuxer(s);
		PssDemuxer.Packet p;
		while (!done[0] && (p = d.next()) != null) {
			if (p.stream == PssDemuxer.VIDEO) {
				v.feed(p.data, 0, p.len);
			}
		}
		v.flush();
		String base = s.name().replace('.', '_');
		for (VideoFrame f : keep) {
			Path o = outDir.resolve(String.format("%s_%06.2f.png", base, f.time()));
			ImageIO.write(toImage(f), "png", o.toFile());
			System.out.println(o + "  frame " + f.index());
		}
	}

	/** BT.601 제한 범위 YUV → RGB (엔진 셰이더와 같은 식). */
	public static BufferedImage toImage(VideoFrame f) {
		BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < f.height(); y++) {
			for (int x = 0; x < f.width(); x++) {
				double Y = 1.164 * ((f.y()[y * f.width() + x] & 0xFF) - 16);
				int ci = (y / 2) * (f.width() / 2) + x / 2;
				double U = (f.u()[ci] & 0xFF) - 128, V = (f.v()[ci] & 0xFF) - 128;
				int r = clamp(Y + 1.596 * V), g = clamp(Y - 0.392 * U - 0.813 * V), b = clamp(Y + 2.017 * U);
				img.setRGB(x, y, r << 16 | g << 8 | b);
			}
		}
		return img;
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	static void bench(MovieSource s, double seconds) throws IOException {
		int[] n = {0};
		double[] last = {0};
		Mpeg2Video v = new Mpeg2Video(f -> {
			n[0]++;
			last[0] = f.time();
		});
		PssDemuxer d = new PssDemuxer(s);
		long t0 = System.nanoTime();
		PssDemuxer.Packet p;
		while (last[0] < seconds && (p = d.next()) != null) {
			if (p.stream == PssDemuxer.VIDEO) {
				v.feed(p.data, 0, p.len);
			}
		}
		v.flush();
		double wall = (System.nanoTime() - t0) / 1e9;
		System.out.printf("%s %dx%d: %d 프레임 (%.1f초 분량) / %.2f초 = %.1f fps (디코더만 %.1f fps), 오류 %d%n",
				s.name(), v.width(), v.height(), n[0], n[0] / v.fps(), wall, n[0] / wall,
				v.decoded() / (v.decodeNanos() / 1e9), v.errors());
	}

	static void wav(MovieSource s, Path out) throws IOException {
		SsAudio audio = new SsAudio();
		java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
		PssDemuxer d = new PssDemuxer(s);
		PssDemuxer.Packet p;
		short[] tmp = new short[0];
		while ((p = d.next()) != null) {
			if (p.stream == PssDemuxer.PRIVATE1 && p.sub == 0xFF) {
				audio.feed(p.data, 0, p.len);
				if (tmp.length < audio.available()) {
					tmp = new short[audio.available()];
				}
				int k = audio.drain(tmp, 0, audio.available());
				for (int i = 0; i < k; i++) {
					pcm.write(tmp[i]);
					pcm.write(tmp[i] >> 8);
				}
			}
		}
		byte[] data = pcm.toByteArray();
		try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
			o.writeBytes("RIFF");
			o.writeInt(Integer.reverseBytes(36 + data.length));
			o.writeBytes("WAVEfmt ");
			o.writeInt(Integer.reverseBytes(16));
			o.writeShort(Short.reverseBytes((short) 1));
			o.writeShort(Short.reverseBytes((short) audio.channels));
			o.writeInt(Integer.reverseBytes(audio.rate));
			o.writeInt(Integer.reverseBytes(audio.rate * audio.channels * 2));
			o.writeShort(Short.reverseBytes((short) (audio.channels * 2)));
			o.writeShort(Short.reverseBytes((short) 16));
			o.writeBytes("data");
			o.writeInt(Integer.reverseBytes(data.length));
			o.write(data);
		}
		System.out.println(out + ": " + audio + ", " + data.length + " B");
	}
}
