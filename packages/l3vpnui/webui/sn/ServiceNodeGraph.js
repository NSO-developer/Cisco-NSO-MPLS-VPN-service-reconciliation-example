define([
], function(
) {


function NodeGraph() {
    this.type = 'l3vpn';
}

NodeGraph.prototype.getNode = function(args) {
    var service = args.sel('g').call(args.force.drag);

    service.append('circle')
        .attr("class", "node")
        .attr("r", 25)
        .attr('stroke', 'black')
        .style("fill", 'white');

    service.append('text')
        .attr('dy', 4)
        .attr('text-anchor', 'middle')
        .text(args.d.name);

    var events = args.serviceEvents;

    if (events && _.isFunction(events.click)) {
        service.on('click', function(d) {
            events.click(d);
        });
    }

    return service.node();
};

return NodeGraph;

});


