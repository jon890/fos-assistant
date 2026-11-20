package com.bifos.assistant.notification.infra;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 웹 푸시가 쓰는 P-256 키를 바이트와 JDK 키 사이에서 바꾼다(RFC 8291, RFC 8292).
 *
 * <p>공개 키는 비압축 점 65바이트({@code 0x04 || X || Y}), 비밀 키는 32바이트 정수다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class P256 {

    /** 비압축 공개 점의 길이다. */
    public static final int PUBLIC_BYTES = 65;

    private static final int COORDINATE_BYTES = 32;
    private static final ECParameterSpec PARAMS = load();


    public static ECParameterSpec params() {
        return PARAMS;
    }


    private static ECParameterSpec load() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("P-256 parameters are not available", ex);
        }
    }


    /** 비압축 점 65바이트를 공개 키로 바꾼다. 길이와 머리 바이트를 보고, 곡선 위의 점인지는 {@code y^2 = x^3 + ax + b} 로 직접 본다. 아니면 {@link IllegalArgumentException} 이다. */
    public static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed == null || uncompressed.length != PUBLIC_BYTES || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("P-256 public key must be a 65 byte uncompressed point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + COORDINATE_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + COORDINATE_BYTES, PUBLIC_BYTES));
        if (!onCurve(x, y)) {
            throw new IllegalArgumentException("P-256 public key is not a point on the curve");
        }
        try {
            return (ECPublicKey)KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("P-256 public key is not a point on the curve", ex);
        }
    }


    private static boolean onCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = PARAMS.getCurve();
        BigInteger p = ((ECFieldFp)curve.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }


    /** 32바이트 정수를 비밀 키로 바꾼다. */
    public static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar == null || scalar.length != COORDINATE_BYTES) {
            throw new IllegalArgumentException("P-256 private key must be 32 bytes");
        }
        try {
            return (ECPrivateKey)KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), params()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("P-256 private key is not valid", ex);
        }
    }


    /** 공개 키를 비압축 점 65바이트로 적는다. */
    public static byte[] encode(ECPublicKey key) {
        byte[] out = new byte[PUBLIC_BYTES];
        out[0] = 0x04;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 1 + COORDINATE_BYTES);
        return out;
    }


    /** 정수를 32바이트 big-endian 으로 맞춰 적는다. 앞의 부호 바이트는 버리고 모자라면 앞을 0 으로 채운다. */
    static void copyFixed(BigInteger value, byte[] out, int offset) {
        byte[] raw = value.toByteArray();
        int from = Math.max(0, raw.length - COORDINATE_BYTES);
        int length = raw.length - from;
        System.arraycopy(raw, from, out, offset + COORDINATE_BYTES - length, length);
    }
}
