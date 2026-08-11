package com.aios.tools.emulatorcontrol;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Minimal authenticated client for test-only Android Emulator control calls. */
public final class EmulatorControlMain {
    private static final String SEND_SMS_METHOD =
            "android.emulation.control.EmulatorController/sendSms";
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final MethodDescriptor.Marshaller<byte[]> BYTES =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(InputStream stream) {
                    try {
                        return stream.readAllBytes();
                    } catch (IOException error) {
                        throw new IllegalStateException("cannot read emulator response", error);
                    }
                }
            };

    private EmulatorControlMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"send-sms".equals(args[0])) {
            throw new IllegalArgumentException(
                    "usage: send-sms <emulator-discovery.ini> <source-address> <text>");
        }
        Path discovery = Path.of(args[1]).toAbsolutePath().normalize();
        String source = args[2];
        String text = args[3];
        if (!Files.isRegularFile(discovery)) {
            throw new IllegalArgumentException("emulator discovery file is unavailable");
        }
        if (!source.matches("[+0-9() .-]{3,40}")) {
            throw new IllegalArgumentException("source address is not GSM-like");
        }
        if (!text.matches("[A-Z0-9]{1,64}")) {
            throw new IllegalArgumentException("test SMS text is invalid");
        }

        Map<String, String> properties = readProperties(discovery);
        int port = parsePort(properties.get("grpc.port"));
        String token = properties.getOrDefault("grpc.token", "");
        if (token.isBlank() || token.length() > 4096) {
            throw new IllegalArgumentException("emulator gRPC token is unavailable");
        }

        ManagedChannel managed = OkHttpChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext()
                .build();
        try {
            Metadata headers = new Metadata();
            headers.put(AUTHORIZATION, "Bearer " + token);
            Channel authenticated = ClientInterceptors.intercept(
                    managed, MetadataUtils.newAttachHeadersInterceptor(headers));
            MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SEND_SMS_METHOD)
                    .setRequestMarshaller(BYTES)
                    .setResponseMarshaller(BYTES)
                    .build();
            byte[] response = ClientCalls.blockingUnaryCall(
                    authenticated,
                    method,
                    CallOptions.DEFAULT.withDeadlineAfter(Duration.ofSeconds(10).toMillis(),
                            TimeUnit.MILLISECONDS),
                    encodeSms(source, text));
            int status = decodePhoneResponse(response);
            if (status != 0) {
                throw new IllegalStateException("emulator rejected SMS with status " + status);
            }
            System.out.println("SMS_DELIVERED");
        } finally {
            managed.shutdownNow();
            managed.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    static byte[] encodeSms(String source, String text) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeString(output, 1, source);
        writeString(output, 2, text);
        return output.toByteArray();
    }

    static int decodePhoneResponse(byte[] payload) {
        int[] cursor = {0};
        int response = 0;
        while (cursor[0] < payload.length) {
            long tag = readVarint(payload, cursor);
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (field == 1 && wireType == 0) {
                response = Math.toIntExact(readVarint(payload, cursor));
            } else {
                skipField(payload, cursor, wireType);
            }
        }
        return response;
    }

    private static Map<String, String> readProperties(Path source) throws IOException {
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (values.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate emulator discovery property: " + key);
            }
        }
        return values;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1024 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("emulator gRPC port is invalid", error);
        }
    }

    private static void writeString(ByteArrayOutputStream output, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(output, (long) field << 3 | 2);
        writeVarint(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7fL) != 0L) {
            output.write((int) (value & 0x7fL) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static long readVarint(byte[] payload, int[] cursor) {
        long value = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            if (cursor[0] >= payload.length) {
                throw new IllegalArgumentException("truncated emulator response");
            }
            int next = payload[cursor[0]++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("oversized emulator response varint");
    }

    private static void skipField(byte[] payload, int[] cursor, int wireType) {
        switch (wireType) {
            case 0 -> readVarint(payload, cursor);
            case 1 -> cursor[0] = Math.addExact(cursor[0], 8);
            case 2 -> {
                int length = Math.toIntExact(readVarint(payload, cursor));
                cursor[0] = Math.addExact(cursor[0], length);
            }
            case 5 -> cursor[0] = Math.addExact(cursor[0], 4);
            default -> throw new IllegalArgumentException("unsupported emulator response wire type");
        }
        if (cursor[0] > payload.length) {
            throw new IllegalArgumentException("truncated emulator response field");
        }
    }
}
