package com.pleos.cooldown.data.local

import com.pleos.cooldown.domain.model.Baseline

/**
 * 기준선 저장소 (C0-14, C0-15). 저장된 값이 없거나 일부가 빠져 있으면 내장 기본값으로 채운다.
 */
class BaselineRepository(private val dao: BaselineDao) {

    suspend fun load(): Baseline {
        val entity = dao.get() ?: return Baseline.DEFAULT
        val stored = BaselineCodec.decode(entity.json)
        val merged = Baseline.DEFAULT.bySpeedBand.mapValues { (band, defaults) ->
            defaults + stored[band].orEmpty().filterKeys { it in defaults }
        }
        return Baseline(merged, entity.totalTrips, entity.totalMinutes)
    }

    suspend fun save(baseline: Baseline) {
        dao.upsert(
            BaselineEntity(
                json = BaselineCodec.encode(baseline.bySpeedBand),
                totalTrips = baseline.totalTrips,
                totalMinutes = baseline.totalDrivingMinutes,
            )
        )
    }

    /** "운전 습관 다시 배우기" */
    suspend fun reset() {
        dao.clear()
    }
}
