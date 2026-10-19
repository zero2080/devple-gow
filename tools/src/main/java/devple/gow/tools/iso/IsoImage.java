package devple.gow.tools.iso;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * PS2 DVD 이미지(ISO9660) 리더.
 * <p>
 * PS2 DVD9(2층) 이미지는 레이어마다 독립된 ISO9660 볼륨을 갖는다.
 * 갓오브워2(SCKA_300.02) 기준으로 레이어 1의 PVD는 "레이어 0 볼륨 크기" 섹터에 바로 위치한다.
 * 따라서 레이어 1 볼륨의 기준 섹터는 (레이어 0 크기 - 16)이며,
 * 레이어 1 디렉토리의 LBA는 이 기준 섹터에 대한 상대값이다.
 * (일부 타이틀은 +16 위치에 두기도 하므로 두 위치를 모두 시도한다.)
 */
public final class IsoImage implements AutoCloseable {

	public static final int SECTOR = 2048;
	private static final int PVD_SECTOR = 16;

	private final FileChannel ch;
	private final List<Volume> volumes = new ArrayList<>();

	public record Volume(int layer, long baseSector, long sizeSectors, String systemId, String volumeId,
						 long rootLba, long rootSize) {
	}

	public IsoImage(Path path) throws IOException {
		this.ch = FileChannel.open(path, StandardOpenOption.READ);
		Volume l0 = readVolume(0, 0);
		if (l0 == null) {
			throw new IOException("ISO9660 PVD를 찾을 수 없음: " + path);
		}
		volumes.add(l0);
		long l0Size = l0.sizeSectors();
		for (long l1Base : new long[]{l0Size - PVD_SECTOR, l0Size}) {
			if ((l1Base + PVD_SECTOR + 1) * SECTOR > ch.size()) {
				continue;
			}
			Volume l1 = readVolume(1, l1Base);
			if (l1 != null) {
				volumes.add(l1);
				break;
			}
		}
	}

	public List<Volume> volumes() {
		return volumes;
	}

	private Volume readVolume(int layer, long base) throws IOException {
		ByteBuffer pvd = readSectors(base + PVD_SECTOR, 1);
		if (pvd.get(0) != 1 || !"CD001".equals(ascii(pvd, 1, 5))) {
			return null;
		}
		long size = Integer.toUnsignedLong(pvd.getInt(80));
		// 루트 디렉토리 레코드는 PVD 오프셋 156
		long rootLba = Integer.toUnsignedLong(pvd.getInt(156 + 2));
		long rootSize = Integer.toUnsignedLong(pvd.getInt(156 + 10));
		return new Volume(layer, base, size, ascii(pvd, 8, 32).trim(), ascii(pvd, 40, 32).trim(), rootLba, rootSize);
	}

	/** 볼륨의 전체 파일 트리를 재귀적으로 나열한다. */
	public List<IsoEntry> list(Volume v) throws IOException {
		List<IsoEntry> out = new ArrayList<>();
		walk(v, "", v.rootLba(), v.rootSize(), out);
		return out;
	}

	public List<IsoEntry> listAll() throws IOException {
		List<IsoEntry> out = new ArrayList<>();
		for (Volume v : volumes) {
			out.addAll(list(v));
		}
		return out;
	}

	private void walk(Volume v, String prefix, long lba, long size, List<IsoEntry> out) throws IOException {
		ByteBuffer buf = readSectors(v.baseSector() + lba, (int) ((size + SECTOR - 1) / SECTOR));
		int pos = 0;
		while (pos < size) {
			int len = Byte.toUnsignedInt(buf.get(pos));
			if (len == 0) {
				// 레코드는 섹터 경계를 넘지 않으므로 다음 섹터로 이동
				pos = (pos / SECTOR + 1) * SECTOR;
				continue;
			}
			long eLba = Integer.toUnsignedLong(buf.getInt(pos + 2));
			long eSize = Integer.toUnsignedLong(buf.getInt(pos + 10));
			boolean isDir = (buf.get(pos + 25) & 0x02) != 0;
			int nameLen = Byte.toUnsignedInt(buf.get(pos + 32));
			byte first = buf.get(pos + 33);
			if (!(nameLen == 1 && (first == 0 || first == 1))) {
				String name = ascii(buf, pos + 33, nameLen);
				int semi = name.indexOf(';');
				if (semi >= 0) {
					name = name.substring(0, semi);
				}
				String path = prefix + "/" + name;
				out.add(new IsoEntry(path, eLba, eSize, isDir, v.layer()));
				if (isDir) {
					walk(v, path, eLba, eSize, out);
				}
			}
			pos += len;
		}
	}

	public ByteBuffer readSectors(long absSector, int count) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(count * SECTOR).order(ByteOrder.LITTLE_ENDIAN);
		long off = absSector * SECTOR;
		while (buf.hasRemaining()) {
			int n = ch.read(buf, off + buf.position());
			if (n < 0) {
				break;
			}
		}
		return buf.flip();
	}

	/** 볼륨 기준 오프셋에서 임의 바이트를 읽는다. */
	public ByteBuffer read(IsoEntry e, long offset, int length) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
		long base = (volumes.get(e.layer()).baseSector() + e.lba()) * SECTOR + offset;
		while (buf.hasRemaining()) {
			if (ch.read(buf, base + buf.position()) < 0) {
				break;
			}
		}
		return buf.flip();
	}

	public void copyTo(IsoEntry e, OutputStream os) throws IOException {
		final int chunk = 1 << 20;
		for (long off = 0; off < e.size(); off += chunk) {
			ByteBuffer b = read(e, off, (int) Math.min(chunk, e.size() - off));
			os.write(b.array(), 0, b.limit());
		}
	}

	private static String ascii(ByteBuffer b, int off, int len) {
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.US_ASCII);
	}

	@Override
	public void close() throws IOException {
		ch.close();
	}
}
