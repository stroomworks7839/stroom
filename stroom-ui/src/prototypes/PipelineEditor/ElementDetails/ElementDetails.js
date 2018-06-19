import React from 'react';
import PropTypes from 'prop-types';

import { compose, branch, renderComponent } from 'recompose';

import { connect } from 'react-redux';

import { Container, Header, Message, Image, Grid, Form } from 'semantic-ui-react';

import { reduxForm } from 'redux-form';

import HorizontalPanel from 'prototypes/HorizontalPanel';

import ElementField from './ElementField';

const enhance = compose(
  connect(
    (state, props) => {
      const pipeline = state.pipelines[props.pipelineId];
      let initialValues;
      let selectedElementId;
      if (pipeline) {
        initialValues = pipeline.selectedElementInitialValues;
        selectedElementId = pipeline.selectedElementId;
      }
      const form = `${props.pipelineId}-elementDetails`;

      return {
        // for our component
        elements: state.elements,
        selectedElementId,
        pipeline,
        // for redux-form
        form,
        initialValues,
      };
    },
    {
      // actions
    },
  ),
  reduxForm(),
  branch(
    props => !props.selectedElementId,
    renderComponent(() => (
      <Container className="element-details">
        <Message>
          <Message.Header>Please select an element</Message.Header>
        </Message>
      </Container>
    )),
  ),
);

const ElementDetails = enhance(({
  pipelineId, pipeline, selectedElementId, elements, onClose,
}) => {
  const element = pipeline.pipeline.elements.add.find(element => element.id === selectedElementId);
  const elementProperties = pipeline.pipeline.properties.add.filter(property => property.element === selectedElementId);
  const elementType = elements.elements[element.type];
  const elementTypeProperties = elements.elementProperties[element.type];

  const title = (
    <React.Fragment>
      <Image
        size="small"
        src={require(`../images/${elementType.icon}`)}
        className="element-details__icon"
      />
      {element.id}
    </React.Fragment>
  );

  const content = (
    <Form className="element-details__form">
      {Object.keys(elementTypeProperties).map(key => (
        <ElementField
          key={key}
          name={key}
          type={elementTypeProperties[key].type}
          description={elementTypeProperties[key].description}
          defaultValue={parseInt(elementTypeProperties[key].defaultValue, 10)}
          value={elementProperties.find(element => element.name === key)}
        />
      ))}
    </Form>
  );

  return (
    <HorizontalPanel
      title={title}
      onClose={() => onClose()}
      content={content}
      titleColumns={6}
      menuColumns={10}
      headerSize="h3"
    />
  );
});

ElementDetails.propTypes = {
  // Set by owner
  pipelineId: PropTypes.string.isRequired,
  onClose: PropTypes.func,

  // Redux state
  pipeline: PropTypes.object.isRequired,
  selectedElementId: PropTypes.string,
  elements: PropTypes.object.isRequired,
};

export default ElementDetails;
