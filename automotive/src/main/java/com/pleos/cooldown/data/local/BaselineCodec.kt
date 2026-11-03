package com.pleos.cooldown.data.local

import com.pleos.cooldown.domain.model.FeatureId
import com.pleos.cooldown.domain.model.FeatureStat
import com.pleos.cooldown.domain.model.SpeedBand

/**
 * 속도대별 특징 분포 ↔ 문자열. 한 줄에 `BAND|FEATURE|mean|variance|samples` 하나.
 *
 * org.json은 JVM 단위 테스트에서 동작하지 않고 의존성을 늘리지 않으려고 직접 쓴다.
 * 알 수 없는 속도대·특징이나 깨진 줄은 건너뛴다 (버전이 바뀌어도 앱이 죽지 않게).
 */
object BaselineCodec {

    private const val SEPARATOR = "|"

    fun encode(bySpeedBand: Map<SpeedBand, Map<FeatureId, FeatureStat>>): String =
        bySpeedBand.flatMap { (band, stats) ->
            stats.map { (id, stat) ->
                listOf(band.name, id.name, stat.mean, stat.variance, stat.samples).joinToString(SEPARATOR)
            }
        }.joinToString("\n")

    fun decode(text: String): Map<SpeedBand, Map<FeatureId, FeatureStat>> {
        val result = mutableMapOf<SpeedBand, MutableMap<FeatureId, FeatureStat>>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 5) return@forEach
            val band = SpeedBand.entries.firstOrNull { it.name == parts[0] } ?: return@forEach
            val id = FeatureId.entries.firstOrNull { it.name == parts[1] } ?: return@forEach
            val mean = parts[2].toDoubleOrNull() ?: return@forEach
            val variance = parts[3].toDoubleOrNull() ?: return@forEach
            val samples = parts[4].toIntOrNull() ?: return@forEach
            result.getOrPut(band) { mutableMapOf() }[id] = FeatureStat(mean, variance, samples)
        }
        return result
    }
}
