<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- The comparison spine, live against Saxon. Untyped = untyped is a string comparison
       (the 10 vs 10.0 row is the trap the strict rule keeps); number() against a literal is
       the explicit numeric read; string less-than is code-point order, which the challenger
       answers byte-wise on UTF-8; and the n/a row is the failed cast reading false. -->
  <xsl:template match="/cs">
    <t><xsl:apply-templates select="c"/></t>
  </xsl:template>
  <xsl:template match="c">
    <r>
      <same><xsl:value-of select="@a = @b"/></same>
      <big><xsl:value-of select="number(@n) &gt; 100"/></big>
      <big2><xsl:value-of select="number(@n) &gt; 100"/></big2>
      <ltm><xsl:value-of select="@w &lt; 'm'"/></ltm>
      <mix><xsl:value-of select="false()"/></mix>
    </r>
  </xsl:template>
</xsl:stylesheet>
