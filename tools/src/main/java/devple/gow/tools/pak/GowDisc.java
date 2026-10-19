package devple.gow.tools.pak;

import devple.gow.tools.iso.IsoEntry;
import devple.gow.tools.iso.IsoImage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

/**
 * 갓오브워 디스크 = ISO + GODOFWAR.TOC + PART1.PAK/PART2.PAK.
 * 게임 데이터 파일(WAD/VAG/VPK/PSS ...)에 이름으로 접근한다.
 */
public final class GowDisc implements AutoCloseable {

	private final IsoImage iso;
	private final GowToc toc;
	private final IsoEntry[] parts = new IsoEntry[2];

	public GowDisc(Path isoPath) throws IOException {
		this.iso = new IsoImage(isoPath);
		IsoEntry tocEntry = null;
		for (IsoEntry e : iso.listAll()) {
			switch (e.path()) {
				case "/GODOFWAR.TOC" -> tocEntry = e;
				case "/PART1.PAK" -> parts[0] = e;
				case "/PART2.PAK" -> parts[1] = e;
				default -> {
				}
			}
		}
		if (tocEntry == null || parts[0] == null) {
			throw new IOException("GODOFWAR.TOC / PART1.PAK 를 찾을 수 없음");
		}
		this.toc = new GowToc(iso.read(tocEntry, 0, (int) tocEntry.size()));
	}

	public IsoImage iso() {
		return iso;
	}

	public GowToc toc() {
		return toc;
	}

	public List<TocEntry> entries() {
		return toc.entries();
	}

	public ByteBuffer read(TocEntry e) throws IOException {
		return iso.read(partOf(e), e.sector() * IsoImage.SECTOR, Math.toIntExact(e.size()));
	}

	/** 파일 내 오프셋에서 일부만 읽는다 (동영상 스트리밍). FileChannel 위치 지정 읽기라 스레드 안전. */
	public ByteBuffer read(TocEntry e, long offset, int length) throws IOException {
		return iso.read(partOf(e), e.sector() * IsoImage.SECTOR + offset, length);
	}

	public void copyTo(TocEntry e, OutputStream os) throws IOException {
		IsoEntry part = partOf(e);
		final int chunk = 1 << 20;
		long base = e.sector() * IsoImage.SECTOR;
		for (long off = 0; off < e.size(); off += chunk) {
			ByteBuffer b = iso.read(part, base + off, (int) Math.min(chunk, e.size() - off));
			os.write(b.array(), 0, b.limit());
		}
	}

	private IsoEntry partOf(TocEntry e) throws IOException {
		IsoEntry p = parts[e.part()];
		if (p == null) {
			throw new IOException("PART" + (e.part() + 1) + ".PAK 없음: " + e.name());
		}
		return p;
	}

	@Override
	public void close() throws IOException {
		iso.close();
	}
}
