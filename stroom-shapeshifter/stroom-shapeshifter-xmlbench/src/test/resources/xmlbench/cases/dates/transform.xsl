<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="xs">
  <!-- The date pair, live against Saxon. The sys element is the honest mirror of the
       nearest-year rule: the stylesheet computes what stroom:format-date reaches for a
       clock to do, from the received field the challenger also reads - both year
       boundaries are in the data. rt round-trips the original offset. -->
  <xsl:template match="/log">
    <t><xsl:apply-templates select="e"/></t>
  </xsl:template>
  <xsl:template match="e">
    <xsl:variable name="mm" select="format-number(index-of(('Jan','Feb','Mar','Apr','May','Jun',
        'Jul','Aug','Sep','Oct','Nov','Dec'), substring(@sys, 1, 3)), '00')"/>
    <xsl:variable name="dd" select="format-number(xs:integer(
        substring-before(substring(@sys, 5), ' ')), '00')"/>
    <xsl:variable name="time" select="substring-after(substring(@sys, 5), ' ')"/>
    <xsl:variable name="recv" select="xs:dateTime(@received)"/>
    <xsl:variable name="ry" select="year-from-dateTime($recv)"/>
    <xsl:variable name="cands" select="for $y in ($ry - 1, $ry, $ry + 1) return
        xs:dateTime(concat($y, '-', $mm, '-', $dd, 'T', $time, 'Z'))"/>
    <xsl:variable name="secs" select="for $c in $cands return
        abs(($c - $recv) div xs:dayTimeDuration('PT1S'))"/>
    <o>
      <utc><xsl:value-of select="format-dateTime(adjust-dateTime-to-timezone(xs:dateTime(@ts),
          xs:dayTimeDuration('PT0S')), '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01]Z')"/></utc>
      <sys><xsl:value-of select="format-dateTime($cands[index-of($secs, min($secs))[1]],
          '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01]Z')"/></sys>
      <ep><xsl:value-of select="format-dateTime(xs:dateTime('1970-01-01T00:00:00Z')
          + xs:decimal(@ms) div 1000 * xs:dayTimeDuration('PT1S'),
          '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01]Z')"/></ep>
      <dur><xsl:value-of select="xs:integer((xs:dateTime(@received) - xs:dateTime(@ts))
          div xs:dayTimeDuration('PT0.001S'))"/></dur>
      <rt><xsl:value-of select="format-dateTime(xs:dateTime(@ts),
          '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01][Z00:00]')"/></rt>
    </o>
  </xsl:template>
</xsl:stylesheet>
