package devple.gow.tools.audio;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 오디오 도구.
 * <pre>
 *   audio:wav  &lt;file.VAG|file.VPK&gt; &lt;out.wav&gt;
 *   audio:all  &lt;gow-rom&gt; &lt;outDir&gt;      (모든 VAG/VPK 변환)
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
			default -> throw new IllegalArgumentException(a[0]);
		}
	}

	private static void convert(Path in, Path out) throws Exception {
		PcmSound s = AudioDecoder.decode(in.getFileName().toString(), Files.readAllBytes(in));
		try (OutputStream os = Files.newOutputStream(out)) {
			s.writeWav(os);
		}
		System.out.printf("%-14s %5dHz %dch %6.1fs%n", s.name(), s.sampleRate(), s.channels(),
				s.samples().length / (double) s.channels() / s.sampleRate());
	}
}
