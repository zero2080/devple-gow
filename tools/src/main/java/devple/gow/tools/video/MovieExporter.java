package devple.gow.tools.video;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 컷신(PSS/PSW) → QuickTime 재생 가능한 .mov (H.264 + PCM).
 * <p>
 * 디코딩은 기존 Java 경로(JCodec MPEG-2, {@link Mpeg2Video})를 그대로 쓰고, <b>인코딩만 macOS 하드웨어</b>
 * (AVFoundation/VideoToolbox) 도우미 {@code tools/native/gowenc.swift} 에 맡긴다.
 * 이유: JCodec 의 H.264 인코더는 baseline/CAVLC + 단순 비트레이트 제어라 파일이 크고 느리다.
 * 120개 전체 변환(약 1시간 분량)을 남은 디스크로 감당하려면 하드웨어 High 프로파일 인코더가 적합하다 (33 문서 §6).
 */
public final class MovieExporter {

	private MovieExporter() {
	}

	/** 도우미 실행 파일. 없으면 swiftc 로 빌드한다. */
	public static Path helper(Path projectRoot) throws IOException, InterruptedException {
		Path bin = projectRoot.resolve("work/bin/gowenc");
		Path src = projectRoot.resolve("tools/native/gowenc.swift");
		if (!Files.isExecutable(bin) || Files.getLastModifiedTime(bin).compareTo(Files.getLastModifiedTime(src)) < 0) {
			Files.createDirectories(bin.getParent());
			Process p = new ProcessBuilder("swiftc", "-O", "-swift-version", "5", src.toString(), "-o", bin.toString())
					.inheritIO().start();
			if (p.waitFor() != 0) {
				throw new IOException("gowenc 빌드 실패 (Xcode Command Line Tools 의 swiftc 필요)");
			}
		}
		return bin;
	}

	/**
	 * @param widescreen PSW (애너모픽 16:9 → 픽셀 비율 4:3 표시 정보)
	 * @return 프레임 수
	 */
	public static int export(MovieSource src, Path out, Path helper, boolean widescreen) throws IOException, InterruptedException {
		Path wav = Files.createTempFile("gowmovie", ".wav");
		try {
			Cli.wav(src, wav);
			Process[] proc = {null};
			OutputStream[] pipe = {null};
			byte[][] frameBuf = {null};
			Mpeg2Video v = new Mpeg2Video(f -> {
				try {
					if (proc[0] == null) {
						// 29.97fps = 30000/1001
						int parH = widescreen ? 4 : 1, parV = widescreen ? 3 : 1;
						proc[0] = new ProcessBuilder(List.of(helper.toString(), Integer.toString(f.width()),
								Integer.toString(f.height()), "30000", "1001", Integer.toString(parH), Integer.toString(parV),
								wav.toString(), out.toString())).redirectOutput(ProcessBuilder.Redirect.INHERIT)
								.redirectError(ProcessBuilder.Redirect.INHERIT).start();
						pipe[0] = new BufferedOutputStream(proc[0].getOutputStream(), 1 << 20);
						frameBuf[0] = new byte[f.width() * f.height() * 3 / 2];
					}
					byte[] b = frameBuf[0];
					int ys = f.y().length, cs = f.u().length;
					System.arraycopy(f.y(), 0, b, 0, ys);
					System.arraycopy(f.u(), 0, b, ys, cs);
					System.arraycopy(f.v(), 0, b, ys + cs, cs);
					pipe[0].write(b);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			PssDemuxer d = new PssDemuxer(src);
			PssDemuxer.Packet p;
			while ((p = d.next()) != null) {
				if (p.stream == PssDemuxer.VIDEO) {
					v.feed(p.data, 0, p.len);
				}
			}
			v.flush();
			if (proc[0] == null) {
				throw new IOException("영상 프레임 없음: " + src.name());
			}
			pipe[0].close();
			if (proc[0].waitFor() != 0) {
				throw new IOException("인코딩 실패: " + src.name());
			}
			return v.decoded();
		} finally {
			Files.deleteIfExists(wav);
		}
	}
}
