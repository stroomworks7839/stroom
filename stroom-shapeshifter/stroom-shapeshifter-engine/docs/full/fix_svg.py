import sys

css_overrides = """
/* Overrides for better visibility on dark backgrounds */
#my-svg .edgePath .path, #my-svg .flowchart-link {
    stroke: #ffffff !important;
    stroke-width: 2px !important;
}
#my-svg marker path, #my-svg marker polygon, #my-svg marker circle {
    fill: #ffffff !important;
    stroke: #ffffff !important;
}

/* Node styling */
#my-svg .node rect, #my-svg .node circle, #my-svg .node ellipse, #my-svg .node polygon, #my-svg .node path {
    stroke-width: 2px !important;
}

/* Stroom nodes */
#my-svg-flowchart-Feeds-0 rect, #my-svg-flowchart-Pipeline-1 rect, #my-svg-flowchart-UI-2 rect {
    fill: #7a6331 !important;
    stroke: #d0aa55 !important;
}

/* Intelligence nodes */
#my-svg-flowchart-Controller-3 rect, #my-svg-flowchart-Dashboard-4 rect, #my-svg-flowchart-AIOrch-5 rect, #my-svg-flowchart-TransformLib-6 rect {
    fill: #3b507d !important;
    stroke: #6fa6e5 !important;
}

/* Engine nodes */
#my-svg-flowchart-Parser-7 rect, #my-svg-flowchart-Editor-8 rect, #my-svg-flowchart-Patterns-9 rect {
    fill: #5a3880 !important;
    stroke: #a274e8 !important;
}

/* Database node */
#my-svg-flowchart-DB-10 path {
    fill: #4a4a4a !important;
    stroke: #999999 !important;
}

/* Make all node text white */
#my-svg .node .label text, #my-svg .node span, #my-svg .node p, #my-svg .node .nodeLabel {
    color: #ffffff !important;
    fill: #ffffff !important;
}

/* Edge labels */
#my-svg .edgeLabel p {
    color: #333333 !important;
    fill: #333333 !important;
    font-weight: bold;
}
#my-svg .labelBkg {
    background-color: #ffffff !important;
    border-radius: 4px;
    padding: 2px 6px;
    opacity: 0.9 !important;
}
"""

with open('diagram-stroom-integration.svg', 'r') as f:
    content = f.read()

# Insert the overrides before </style>
if '</style>' in content:
    content = content.replace('</style>', css_overrides + '</style>')
else:
    print("Error: </style> not found")
    sys.exit(1)

with open('diagram-stroom-integration.svg', 'w') as f:
    f.write(content)
print("SVG updated successfully")
