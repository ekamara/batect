/*
   Copyright 2017 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.journeytests

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on

object HelpJourneyTest : Spek({
    given("the application") {
        on("requesting help for the application") {
            val runner = ApplicationRunner("")
            val result = runner.runApplication(listOf("help"))

            it("prints the help header") {
                assertThat(result.output, containsSubstring("Usage: batect [COMMON OPTIONS] COMMAND [COMMAND OPTIONS]"))
            }

            it("returns a non-zero exit code") {
                assertThat(result.exitCode, !equalTo(0))
            }
        }

        on("requesting help for a command") {
            val runner = ApplicationRunner("")
            val result = runner.runApplication(listOf("help", "run"))

            it("prints the help header") {
                assertThat(result.output, containsSubstring("Usage: batect [COMMON OPTIONS] run [OPTIONS] TASK"))
            }

            it("prints a description of the command") {
                assertThat(result.output, containsSubstring("Run a task."))
            }

            it("returns a non-zero exit code") {
                assertThat(result.exitCode, !equalTo(0))
            }
        }
    }
})