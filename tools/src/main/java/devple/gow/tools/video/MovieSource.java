package devple.gow.tools.video;

import devple.gow.tools.pak.GowDisc;
import devple.gow.tools.pak.TocEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 동영상 바이트 소스. 추출된 파일(gow-rom/NAME.PSS) 또는 ISO 안의 TOC 엔트리를 그대로 읽는다.
 * 동영상은 3.9GB 라 기본 추출(pak-extract)에서 빠지므로 ISO 직접 읽기가 기본 경로다.
 */
public interface MovieSource extends AutoCloseable {

	String name();

	long size();

	/** pos 부터 최대 len 바이트를 dst 에 읽는다. 반환 = 읽은 바이트 수 (끝이면 0). */
	int read(long pos, byte[] dst, int off, int len) throws IOException;

	@Override
	default void close() throws IOException {
	}

	static MovieSource ofFile(Path p) throws IOException {
		FileChannel ch = FileChannel.open(p, StandardOpenOption.READ);
		long size = ch.size();
		String name = p.getFileName().toString();
		return new MovieSource() {
			public String name() {
				return name;
			}

			public long size() {
				return size;
			}

			public int read(long pos, byte[] dst, int off, int len) throws IOException {
				int n = (int) Math.max(0, Math.min(len, size - pos));
				ByteBuffer bb = ByteBuffer.wrap(dst, off, n);
				while (bb.hasRemaining()) {
					if (ch.read(bb, pos + bb.position() - off) < 0) {
						break;
					}
				}
				return bb.position() - off;
			}

			public void close() throws IOException {
				ch.close();
			}
		};
	}

	/** ISO 안 TOC 엔트리. disc 는 호출자가 닫는다 (ownDisc = true 면 이 소스가 닫는다). */
	static MovieSource ofDisc(GowDisc disc, TocEntry e, boolean ownDisc) {
		return new MovieSource() {
			public String name() {
				return e.name();
			}

			public long size() {
				return e.size();
			}

			public int read(long pos, byte[] dst, int off, int len) throws IOException {
				int n = (int) Math.max(0, Math.min(len, e.size() - pos));
				if (n == 0) {
					return 0;
				}
				ByteBuffer b = disc.read(e, pos, n);
				int got = b.remaining();
				b.get(dst, off, got);
				return got;
			}

			public void close() throws IOException {
				if (ownDisc) {
					disc.close();
				}
			}
		};
	}

	/**
	 * 이름(확장자 없이, 대소문자 무관)으로 동영상을 연다. 와이드스크린이면 .PSW 를 먼저 찾는다.
	 * 순서: romDir/NAME.PS? → iso 의 TOC. 없으면 null.
	 */
	static MovieSource open(String name, Path romDir, Path iso, boolean widescreen) throws IOException {
		String base = MovieNames.fileBase(name);
		String[] exts = widescreen ? new String[]{".PSW", ".PSS"} : new String[]{".PSS", ".PSW"};
		if (romDir != null) {
			for (String ext : exts) {
				Path p = devple.gow.tools.content.Content.file(romDir, base + ext); // 모드 rom/ 교체 (49 문서 §3)
				if (Files.isRegularFile(p)) {
					return ofFile(p);
				}
			}
		}
		if (iso != null && Files.isRegularFile(iso)) {
			GowDisc disc = new GowDisc(iso);
			for (String ext : exts) {
				TocEntry e = disc.toc().unique().get(base + ext);
				if (e != null) {
					return ofDisc(disc, e, true);
				}
			}
			disc.close();
		}
		return null;
	}
}
