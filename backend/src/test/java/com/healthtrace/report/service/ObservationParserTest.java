package com.healthtrace.report.service;

import com.healthtrace.report.domain.AbnormalFlag;
import com.healthtrace.report.domain.Observation;
import com.healthtrace.report.domain.ResultKind;
import com.healthtrace.report.service.ObservationParser.ParsedRange;
import com.healthtrace.report.service.ObservationParser.ParsedValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "6.5|EXACT|6.5",
            "120 ↑|EXACT|120",
            "<0.5|LESS_THAN|0.5",
            "≥100|GREATER_THAN|100",
            "＞3|GREATER_THAN|3",
            "阴性|QUALITATIVE|",
            "+|QUALITATIVE|",
            "6.5x|UNKNOWN|",
    })
    void parsesValues(String text, ResultKind kind, String number) {
        ParsedValue v = ObservationParser.parseValue(text);
        assertThat(v.kind()).isEqualTo(kind);
        if (number == null) {
            assertThat(v.numericValue()).isNull();
        } else {
            assertThat(v.numericValue()).isEqualByComparingTo(number);
        }
    }

    @Test
    void blankValueIsUnknown() {
        assertThat(ObservationParser.parseValue("  ").kind()).isEqualTo(ResultKind.UNKNOWN);
    }

    @Test
    void parsesRanges() {
        ParsedRange r = ObservationParser.parseRange("3.5-9.5");
        assertThat(r.low()).isEqualByComparingTo("3.5");
        assertThat(r.high()).isEqualByComparingTo("9.5");
        assertThat(ObservationParser.parseRange("115～150").high()).isEqualByComparingTo("150");
        assertThat(ObservationParser.parseRange("<6").low()).isNull();
        assertThat(ObservationParser.parseRange("<6").high()).isEqualByComparingTo("6");
        assertThat(ObservationParser.parseRange("9-3")).isEqualTo(new ParsedRange(null, null));
        assertThat(ObservationParser.parseRange("阴性")).isEqualTo(new ParsedRange(null, null));
    }

    @Test
    void derivesAbnormalFlagOnlyForExactWithBothBounds() {
        Observation o = new Observation();
        o.setOriginalValue("10.2");
        o.setReferenceText("3.5-9.5");
        ObservationParser.derive(o);
        assertThat(o.getAbnormalFlag()).isEqualTo(AbnormalFlag.HIGH);
        assertThat(o.getNumericValue()).isEqualByComparingTo("10.2");

        o.setOriginalValue("3.0");
        ObservationParser.derive(o);
        assertThat(o.getAbnormalFlag()).isEqualTo(AbnormalFlag.LOW);

        o.setOriginalValue("6.5");
        ObservationParser.derive(o);
        assertThat(o.getAbnormalFlag()).isEqualTo(AbnormalFlag.NORMAL);

        o.setOriginalValue("<0.5");
        o.setReferenceText("0-6");
        ObservationParser.derive(o);
        assertThat(o.getResultKind()).isEqualTo(ResultKind.LESS_THAN);
        assertThat(o.getAbnormalFlag()).isEqualTo(AbnormalFlag.UNKNOWN);
    }
}
