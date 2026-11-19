package com.cashi.fee.bdd

import com.cashi.fee.support.FeeIntegrationTest
import io.cucumber.spring.CucumberContextConfiguration

/** The one class that ties Cucumber to the shared IT context. No other glue class may carry these. */
@CucumberContextConfiguration
@FeeIntegrationTest
class CucumberSpringConfiguration
