<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
  xmlns="event-logging:3"
  xpath-default-namespace="records:2"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  version="2.0">

   <!--
      Adapted from stroom-pipeline/src/main/resources/stroom/benchmark/EVENTS.xsl for the
      head-to-head (design/13): stroom:format-date became a concat with 'T' over
      generator-controlled ISO fields, and the stroom:lookup reference-data call became a
      conditional over the optional Host field — the two Stroom extension functions removed,
      the shape (variables, named template, attribute constructors, conditionals,
      cross-namespace output) kept.
   -->

   <xsl:template match="records">
      <Events
        xsi:schemaLocation="event-logging:3 file://event-logging-v3.0.0.xsd"
        Version="3.0.0">
         <xsl:apply-templates/>
      </Events>
   </xsl:template>

   <xsl:template match="record">
      <xsl:variable name="user" select="data[@name='User']/@value"/>
     <Event>
        <xsl:call-template name="header"/>
        <EventDetail>
           <Description><xsl:value-of select="data[@name='Message']/@value"/></Description>
           <Authenticate>
              <Action>Logon</Action>
              <LogonType>Interactive</LogonType>
              <User>
                 <Id>
                    <xsl:value-of select="$user"/>
                 </Id>
              </User>
              <Data Name="FileNo">
                <xsl:attribute name="Value" select="data[@name='FileNo']/@value"/>
              </Data>
              <Data Name="LineNo">
                <xsl:attribute name="Value" select="data[@name='LineNo']/@value"/>
              </Data>
           </Authenticate>
        </EventDetail>
     </Event>
   </xsl:template>

   <xsl:template name="header">
      <xsl:variable name="date" select="data[@name='Date']/@value"/>
      <xsl:variable name="time" select="data[@name='Time']/@value"/>
      <xsl:variable name="dateTime" select="concat($date, 'T', $time)"/>
      <xsl:variable name="user" select="data[@name='User']/@value"/>

      <EventTime>
         <TimeCreated>
         	<xsl:value-of select="$dateTime"/>
         </TimeCreated>
      </EventTime>
      <EventSource>
         <Generator>CSV</Generator>
         <Device>
            <IPAddress>1.1.1.1</IPAddress>
            <MACAddress>00-00-00-00-00-00</MACAddress>
            <xsl:variable name="host" select="data[@name='Host']/@value"/>
            <xsl:if test="$host">
               <HostName><xsl:value-of select="$host"/></HostName>
            </xsl:if>
         </Device>
         <User>
            <Id><xsl:value-of select="$user"/></Id>
         </User>
      </EventSource>
   </xsl:template>
</xsl:stylesheet>
