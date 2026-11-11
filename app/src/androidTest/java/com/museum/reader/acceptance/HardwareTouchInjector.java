package com.museum.reader.acceptance;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Dedicated rooted-emulator evdev writer: one process preserves real gesture timing. */
public final class HardwareTouchInjector {
    private static void event(FileOutputStream out, int type, int code, int value) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(0).putLong(0).putShort((short) type).putShort((short) code).putInt(value);
        out.write(buffer.array());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || !args[0].equals("/dev/input/event2")) throw new IllegalArgumentException("Dedicated display-0 device only");
        int contacts = Integer.parseInt(args[1]);
        int id = (int) (System.nanoTime() % 60000);
        if (contacts < 1 || contacts > 2 || (args.length - 2) % 2 != 0) throw new IllegalArgumentException("Invalid contacts");
        long started = System.nanoTime();
        try (FileOutputStream out = new FileOutputStream(args[0])) {
            for (int contact = 0; contact < contacts; contact++) {
                if (contact > 0) Thread.sleep(80);
                event(out, 3, 47, 0);
                event(out, 3, 57, id + contact);
                event(out, 3, 58, 512);
                for (int index = 2; index < args.length; index += 2) {
                    if (index > 2) Thread.sleep(25);
                    event(out, 3, 53, Integer.parseInt(args[index]));
                    event(out, 3, 54, Integer.parseInt(args[index + 1]));
                    event(out, 0, 0, 0);
                }
                Thread.sleep(25);
                event(out, 3, 58, 0);
                event(out, 3, 57, -1);
                event(out, 0, 0, 0);
            }
        }
        System.out.println("OK contacts=" + contacts + " durationMs=" + (System.nanoTime() - started) / 1000000);
    }
}
