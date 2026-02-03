package com.meshcipher.data.recovery

import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShamirSecretSharing @Inject constructor() {

    // secp256k1 prime - larger than any 32-byte secret
    private val prime = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
        16
    )

    fun split(
        secret: ByteArray,
        totalShards: Int,
        threshold: Int
    ): List<ByteArray> {
        require(threshold <= totalShards) { "Threshold must be <= total shards" }
        require(threshold >= 2) { "Threshold must be >= 2" }

        val secretInt = BigInteger(1, secret)
        require(secretInt < prime) { "Secret too large" }

        val coefficients = mutableListOf(secretInt)
        repeat(threshold - 1) {
            coefficients.add(
                BigInteger(prime.bitLength(), SecureRandom()).mod(prime)
            )
        }

        return (1..totalShards).map { x ->
            val xBig = BigInteger.valueOf(x.toLong())
            val y = evaluatePolynomial(coefficients, xBig)
            encodeShard(x, y)
        }
    }

    fun combine(shards: List<ByteArray>): ByteArray {
        require(shards.size >= 2) { "Need at least 2 shards" }

        val points = shards.map { decodeShard(it) }
        val secret = lagrangeInterpolation(points)

        return secret.toByteArray().let { bytes ->
            // Remove leading zero byte if present (BigInteger sign byte)
            if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }
    }

    private fun evaluatePolynomial(
        coefficients: List<BigInteger>,
        x: BigInteger
    ): BigInteger {
        var result = BigInteger.ZERO
        var xPower = BigInteger.ONE

        coefficients.forEach { coeff ->
            result = result.add(coeff.multiply(xPower)).mod(prime)
            xPower = xPower.multiply(x).mod(prime)
        }

        return result
    }

    private fun lagrangeInterpolation(
        points: List<Pair<Int, BigInteger>>
    ): BigInteger {
        var result = BigInteger.ZERO

        points.forEach { (i, yi) ->
            var numerator = BigInteger.ONE
            var denominator = BigInteger.ONE

            points.forEach { (j, _) ->
                if (i != j) {
                    numerator = numerator.multiply(
                        BigInteger.valueOf(-j.toLong())
                    ).mod(prime)
                    denominator = denominator.multiply(
                        BigInteger.valueOf((i - j).toLong())
                    ).mod(prime)
                }
            }

            val term = yi
                .multiply(numerator)
                .multiply(denominator.modInverse(prime))
                .mod(prime)

            result = result.add(term).mod(prime)
        }

        return result
    }

    private fun encodeShard(x: Int, y: BigInteger): ByteArray {
        val xBytes = x.toString().toByteArray()
        val yBytes = y.toByteArray()

        return byteArrayOf(xBytes.size.toByte()) + xBytes + yBytes
    }

    private fun decodeShard(shard: ByteArray): Pair<Int, BigInteger> {
        val xSize = shard[0].toInt()
        val xBytes = shard.sliceArray(1 until 1 + xSize)
        val yBytes = shard.sliceArray(1 + xSize until shard.size)

        val x = String(xBytes).toInt()
        val y = BigInteger(1, yBytes)

        return x to y
    }
}
