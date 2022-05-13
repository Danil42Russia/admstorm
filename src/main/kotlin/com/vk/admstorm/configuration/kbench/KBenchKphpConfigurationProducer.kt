package com.vk.admstorm.configuration.kbench

class KBenchKphpConfigurationProducer : KBenchBaseConfigurationProducer() {
    override fun configurationId() = KBenchKphpConfigurationType.ID
}

class KBenchPhpConfigurationProducer : KBenchBaseConfigurationProducer() {
    override fun configurationId() = KBenchPhpConfigurationType.ID
    override fun benchType() = KBenchType.BenchPhp
    override fun namePrefix() = "PHP Bench"
}

class KBenchKphpVsPhpConfigurationProducer : KBenchBaseConfigurationProducer() {
    override fun configurationId() = KBenchKphpVsPhpConfigurationType.ID
    override fun benchType() = KBenchType.BenchVsPhp
    override fun namePrefix() = "PHP vs KPHP Bench"
}
